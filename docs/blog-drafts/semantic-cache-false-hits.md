---
title: "Why my LLM gateway's semantic cache gives wrong answers 8% of the time, and how I measured it"
status: draft
---

# Why my LLM gateway's semantic cache gives wrong answers 8% of the time, and how I measured it

Semantic caching sounds like an easy win. You embed each prompt, store the response next to the vector, and when a new prompt is close enough to an old one you return the old answer. The provider isn't called and the user waits a few milliseconds instead of a few seconds.

I built one into [Aether Gateway](https://github.com/Ashish-CodeJourney/aether-gateway), a self-hosted, OpenAI-compatible LLM gateway in Java. Then I measured it against a labelled corpus. At the operating point I picked, it serves a wrong cached answer for **8% of the prompt pairs designed to trip it up**, and it catches only about 13% of the genuine paraphrases it exists to catch.

*Update: after the guard learned negation, units and spelled-out numbers, the same corpus measures 0 of 50 false hits and a 14.5% hit rate. I explain [at the end](#update-working-through-the-to-do-list) why I don't trust that 0%.*

This post covers how I measured that, why it happens, and what I changed because of it. Every number comes from the project's [`BENCHMARKS.md`](https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/BENCHMARKS.md) and the [cache correctness design doc](https://ashish-codejourney.github.io/aether-gateway/docs/design/cache-correctness). The raw CSVs are committed alongside them.

First, a precise definition, because the headline needs one. "8%" is **not** 8% of all traffic. It is the share of *adversarial* pairs (two prompts that look alike but need different answers) where the second prompt received the first prompt's cached answer. How often that happens in production depends on how many near-miss prompts your real traffic contains. The rate per near-miss is what the cache controls, so that's the rate I measured.

## The setup

The cache has two layers:

1. **Exact match.** The request is hashed and looked up in Redis, then in Postgres. Identical requests get identical answers. This layer is boring and correct.
2. **Semantic match.** On an exact miss, the prompt is embedded with **all-MiniLM-L6-v2** (384 dimensions, running in-process through ONNX). The gateway then pulls the five nearest neighbours from a **pgvector HNSW index** (`m = 16`, `ef_construction = 64`) and returns the first one whose cosine similarity clears the threshold.

Some requests never reach either layer. The cache is skipped when `temperature > 0.3`, when the request includes tools, or when the caller sends `X-Aether-No-Cache`. Each API key gets its own cache namespace, so one tenant's answers can never be served to another.

The semantic layer is where the trouble is.

## The corpus

You can't measure a false-hit rate without knowing which answers are wrong, so I built a hand-labelled corpus of **502 prompts**:

- **76 near-duplicate pairs**: paraphrases that *should* share an answer. "What's the capital of France?" and "Can you tell me France's capital city?"
- **50 adversarial pairs**: prompts that look alike but *must not* share an answer.
- An **unrelated** bucket, to fill the vector index with realistic noise.
- A **long-context** bucket of 100 entries, used for latency and embedding-cost behaviour rather than hit rate.

That gives two metrics:

- **Hit rate**: the share of the 76 near-duplicate pairs where the paraphrase was served from cache. Higher is better.
- **False-hit rate**: the share of the 50 adversarial pairs where the second prompt was served the first prompt's answer. Lower is better, and this one is a correctness defect, not a performance number.

My targets were at least 35% hit rate and at most 5% false-hit rate. I missed both.

## Experiment 1: similarity alone

First I swept the cosine threshold with nothing but raw embedding similarity, using the real model on the real corpus with no mocks.

Every threshold I tested had a nonzero false-hit rate. The two operating points that matter:

| Threshold | Hit rate | False-hit rate |
|---|---|---|
| 0.94 (no guard) | 14.5% | 28.0% |
| 0.95 (no guard) | 10.5% | 24.0% |

That's roughly one wrong answer for every four adversarial pairs, at thresholds most people would call strict.

The cause is how general-purpose sentence encoders work. The *shape* of a sentence dominates its embedding much more than the single token that changes the answer. "What is 2+2" and "What is 2+3" sit almost on top of each other in vector space. Any threshold high enough to separate them also throws away a large share of real paraphrases. Any threshold low enough to keep useful paraphrases lets factually wrong pairs through.

You can't tune your way out with the threshold alone. The curve has no setting with both a useful hit rate and a low false-hit rate.

## Experiment 2: add a guard

The fix I chose is a cheap, deterministic check that runs after the threshold gate. `EntityNumericGuard` pulls a "fingerprint" from each prompt:

- **Numbers**: digit sequences (`-?\d+(?:\.\d+)?`).
- **Dates**: ISO (`2024-01-01`), slash (`1/5/2024`) and named-month (`January 5, 2024`) formats.
- **Named entities**: runs of capitalized words, minus a stopword list so that "What is…" at the start of a sentence doesn't count as an entity but "France" does.

If the candidate's fingerprint doesn't exactly match the stored entry's fingerprint, the hit is rejected, however high the similarity score. The guard is a heuristic with no network calls, not a trained NER model. It adds no latency worth mentioning and has no dependencies.

The ablation at threshold 0.94:

| | Hit rate | False-hit rate |
|---|---|---|
| No guard | 14.5% | 28.0% |
| Guard on | 13.2% | **8.0%** |

The false-hit rate fell 20 percentage points. In absolute terms, 14 of the 50 adversarial pairs got through without the guard and 4 get through with it. The price was 1.3 points of recall: one of 11 correct paraphrase hits was also rejected.

The guard can only *reject* candidates. It never adds hits. So at every threshold in the sweep, the guarded hit rate and the guarded false-hit rate are each at or below the unguarded ones. The experiment's own test asserts this at every threshold, not just at 0.94.

That leaves the production numbers at **13.16% hit rate and 8.0% false-hit rate**.

## What still gets through

The remaining 8% isn't random. It follows directly from what the guard is designed to look at, which is numbers, dates and named entities, and nothing else. I found three categories, all confirmed against the corpus:

**1. Negation and polarity.** "The library is open" vs "The library is closed." "Revenue increased" vs "revenue decreased." "Expected" vs "not expected." None of these changes a number, date or proper noun, so the guard has nothing to compare. This category causes more false hits than any other, at every threshold measured.

**2. Spelled-out numbers.** The number regex matches digits only. "The vaccine requires two doses" and "The vaccine requires three doses" have a cosine similarity of **0.91**, and the guard reports their fingerprints as equal, so it would allow the hit. The digit version is caught: "The hotel has 120 rooms" vs "The hotel has 220 rooms" is rejected. I found this while picking example pairs for the acceptance tests.

**3. Common nouns that carry the fact.** "Open on weekdays" vs "open on weekends." Neither word is capitalized or numeric, so the guard doesn't see the difference. I found this one the same way and swapped the pair out of the acceptance feature file for one the guard does catch. The gap itself is still there.

None of these is a bug in the sense of the code doing something other than what it was designed to do. They mark the edge of a guard whose scope I deliberately kept narrow. But a user who gets "closed" when the answer is "open" won't care about that distinction.

## Would a bigger embedding model help?

This was the obvious next question, so I tested it. I compared all-MiniLM-L6-v2 with **all-mpnet-base-v2** (768 dimensions) on the same corpus at the same 0.94 threshold:

| Metric | MiniLM-L6 (384d) | mpnet-base-v2 (768d) |
|---|---|---|
| Hit rate at 0.94 | 14.5% | 14.5% |
| False-hit rate at 0.94 | 28.0% | 36.0% |
| Mean embed() latency | 7.23 ms | 41.48 ms |
| Bytes per cached vector | 1536 | 3072 |

At this fixed threshold, the larger model had the same hit rate, a *higher* false-hit rate, 5.7 times the embedding latency and twice the storage. The cache embeds every incoming prompt synchronously, so every request would pay that latency.

One caveat: I didn't re-tune mpnet to its own best threshold. This was a fixed-threshold comparison, and mpnet might look better at a different point on its own curve. Still, "use a bigger model" isn't a free fix, and on this measurement it wasn't a fix at all.

## Was it at least saving money?

Barely. I replayed all 502 prompts through the real cost calculator with the cache on and off:

| Scenario | Total spend |
|---|---|
| No cache | $0.2392065 |
| With cache | $0.2388750 |

That's a **0.1%** reduction. Of the 126 cacheable pairs, the cache avoided 14 requests (11.1% by count). Ten of those were correct paraphrase hits. **Four were the false hits**, where a wrong answer was served and counted as savings.

That last point is uncomfortable. A naive "cache savings" dashboard would count those four as wins. The simulation reports them honestly: false hits do lower spend, and they are still a correctness defect.

The total is also skewed by the corpus. The long-context bucket accounts for 96.1% of simulated spend, and none of it is cache-eligible. Traffic dominated by short, repeated queries would show a much larger percentage saving from the same cache behaviour. That's a property of the traffic mix, not the cache.

## What I changed because of this

**The semantic cache is now labelled experimental.** The README leads with exact-match caching, which is correct by construction, and gives the semantic numbers as they are: 13.16% hit rate and 8.0% false-hit rate.

**Exact-only caching should be the default.** While writing this I found that the routing config couldn't express it: enabling `cache` on a route turned on exact *and* semantic matching together, and the per-route `threshold` key was parsed but never used. Both are fixed. A route now opts out of semantic matching with `semantic: false` in its `cache:` block, which skips the embedding and the vector search entirely, and the shipped example configs use it. The per-route `threshold` is honoured, and the per-request `X-Aether-Cache-Threshold` header can only make matching stricter, never looser, because anonymous callers share a cache namespace.

**The guard has a clear to-do list.** Negation is the largest contributor and probably the cheapest to address: a small check for "not", "no", "never" and antonym pairs near the matched span. Spelled-out numbers need a word-to-number lookup before fingerprinting. Common nouns that carry the fact are harder, and that's where a real NER model or a small cross-encoder re-ranker would start to pay for itself.

**The corpus stays in the repo.** Each of those changes will be judged by the same experiment, and "the false-hit rate went down" will be a number in a CSV, not a claim.

## Update: working through the to-do list

I worked through the guard's to-do list and re-ran experiment 2 against the same corpus.

First I read the four false hits that got through at 0.94. They weren't the ones I expected. Only one was negation ("Is aspirin safe" vs "Is aspirin not safe"). One was a time of day ("6:45pm" vs "6:45am"), one a unit ("60 minutes" vs "60 seconds") and one a common noun ("weekdays" vs "weekends"). None was a spelled-out number. "Two doses" vs "three doses" scores 0.91 similarity, so it never reached the guard at 0.94.

The guard's fingerprint now also records:

- whether the prompt is negated at all
- spelled-out numbers converted to digits ("twenty-one" becomes 21)
- the unit, currency or am/pm attached to a number
- which side of a short list of common opposites the prompt takes ("open"/"closed", "rose"/"fell", "weekdays"/"weekends")

It also stopped treating "Write" and "In" at the start of a sentence as named entities. That bug was the cause of the one paraphrase hit the old guard had blocked. It's still a word-list heuristic with no model behind it, and it costs tens of microseconds per prompt.

| At 0.94 | Hit rate | False-hit rate |
|---|---|---|
| No guard | 14.5% | 28.0% |
| Old guard | 13.2% | 8.0% |
| New guard | **14.5%** | **0.0%** (0 of 50) |

**I don't trust that 0%.** I wrote the new checks after reading exactly which pairs got through, so the guard fits this corpus by construction. To get a less flattering number, I then wrote 20 new adversarial pairs and 10 new paraphrase pairs, and checked them against the frozen guard. The new guard rejected **6 of the 20** adversarial pairs, where the old one rejected 1. It let 8 of the 10 paraphrases through, where the old one let 9 through. Faster vs slower, fixed vs variable rate, tenant vs landlord, penicillin vs amoxicillin and left vs right all still pass. A word list can't cover them, and that's where a small NER model or a cross-encoder re-ranker would start to earn its place.

The sweep now shows a 0% false-hit rate on this corpus from 0.81 upward, so lowering the threshold looks tempting: 0.90 gives a 43% hit rate. I left the default at 0.94, and semantic matching stays opt-in, because the out-of-sample check says the 0% wouldn't hold up on real traffic.

## What I'd tell someone adding a semantic cache

1. **Build the adversarial set before you pick a threshold.** If you only label paraphrases, you only measure the upside, and you'll pick a threshold that looks great.
2. **Similarity alone won't get you there.** On my corpus, no threshold had both a useful hit rate and a false-hit rate under 24%.
3. **A dumb guard does most of the work.** A regex-and-capitalization heuristic cut false hits from 28% to 8% and cost 1.3 points of recall.
4. **Know what your guard can't see,** and write it down. Negation, spelled-out numbers and meaning-bearing common nouns all slip through a fingerprint guard.
5. **Don't count false hits as savings.**
6. **Ship exact-match first.** On the same stack it gave an 8.9 ms p95 cache-hit latency and by construction never returns one prompt's answer for a different prompt.

Everything here, including the corpus, the experiments, the raw CSVs and the `make bench` target that regenerates them, is MIT-licensed:

**https://github.com/Ashish-CodeJourney/aether-gateway**
