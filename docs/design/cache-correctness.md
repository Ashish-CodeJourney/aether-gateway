---
title: "Semantic cache correctness"
---

How Phase 07 (M4)'s semantic cache stays a cost optimisation rather than
a correctness liability, per the PRD's F4 correctness note. Written
alongside the M4 exit criterion measurement; see
`benchmark/results/m4-threshold-sweep.md` for the raw numbers this
document explains.

## The tradeoff, and why a threshold alone is not enough

Embedding similarity alone cannot distinguish "what is 2+2" from "what
is 2+3" - both embed extremely close together, since the *shape* of the
sentence dominates a general-purpose sentence encoder's representation
far more than the one token that actually changes its answer. Any
similarity threshold high enough to reject this pair also rejects a
meaningful fraction of genuine paraphrases, and any threshold low enough
to keep hit rate useful lets a meaningful fraction of factually-wrong
pairs through. The PRD's mitigation is to not rely on the threshold
alone: pair it with a hard, threshold-independent guard.

## The entity/numeric guard

`EntityNumericGuard` (`gateway-core`) reduces a prompt to a
"fingerprint": the set of fact-bearing tokens it contains. A semantic
hit is rejected outright, regardless of similarity score, if the
candidate's fingerprint doesn't exactly match the stored entry's. This
runs in `CacheAdapter.lookup` after the threshold gate, for every
candidate the threshold gate would otherwise accept. The stored side is
computed once at write time and persisted with the entry.

Extraction is a deliberate, in-process, no-network heuristic, not a
trained NER or NLI model:

- **Numbers**: digit sequences (`-?\d+(?:\.\d+)?`), after spelled-out
  cardinals are rewritten to digits ("two" -> 2, "twenty-one" -> 21,
  "one hundred and five" -> 105, "3 million" -> 3000000) and
  digit-grouping commas are dropped ("4,200" -> 4200). A lone "one" is
  not converted because it is usually a pronoun or determiner ("which
  one", or "in one paragraph" meaning "a single"). Ordinal words
  ("first", "second") are not converted either: "second" is also a time
  unit, and "first" is usually a discourse marker.
- **Dates**: ISO (`2024-01-01`), slash (`1/5/2024`), and named-month
  (`January 5, 2024`) patterns, normalised so that "March 3rd" equals
  "March 3" and "December 1, 2026" equals "December 1 2026".
- **What a number measures**: the unit directly after a number ("60
  minutes" vs "60 seconds", "2 kg" vs "2 pounds", "12%" vs "12
  points"), the currency before or after it ("$4,200" vs "€4,200"), and
  the time of day ("6:45pm" vs "6:45am"; "3 PM" and "3 p.m." are equal).
- **Negation**: whether the prompt contains a negator at all: "not",
  "no", "never", "none", "nobody", "nothing", "neither", "nor",
  "without", "cannot", or any "n't" contraction (straight or curly
  apostrophe). The check is presence-based, not scope-aware. Negation
  in one prompt but not the other is a mismatch, and two negated prompts
  can still match.
- **Oppositions**: which side of a short, generic lexicon of opposites
  the prompt takes: open/closed, increase/decrease (rose/fell),
  higher/lower (maximum/minimum, at least), before/after,
  weekday/weekend, parts of the day, safe/unsafe, true/false,
  enabled/disabled, allowed/denied, accepted/rejected, passed/failed,
  buy/sell, ascending/descending, public/private, online/offline,
  excluded, and compass directions. Words too common to carry polarity
  reliably ("more", "close", "drop", "pass", "include") are left out so
  that paraphrases using them aren't rejected. For those pairs, the
  rarer side alone ("failed", "excluded") is enough to make the
  fingerprints differ.
- **Named entities**: capitalized-word runs, minus sentence-initial
  function words, imperatives and contraction stems ("What", "In",
  "Our", "Write", "Recommend", "Isn"). As a result, "Write a Python
  function" and "Give me a Python function" both fingerprint to
  `entity:python`, while "Paris is the capital of France" still yields
  `entity:paris`.

The rule is plain set equality. A pairwise rule that rejects only when
both prompts take *different* sides would be more lenient on
paraphrases. But `CacheAdapter` compares a persisted fingerprint string,
so every check the guard makes has to survive that round trip. Set
equality also keeps the semantics uniform: a fact-bearing token present
in only one prompt is a mismatch, exactly as an added number always
was. Fingerprint tokens never contain a comma, which is the persistence
delimiter. Named-month date tokens used to contain one, which made every
entry carrying such a date unmatchable on read.

ADR-007 scopes Spring AI usage to the embedding model only. A real NER
model is out of scope for this project, and the guard is explicitly a
heuristic safety net layered on top of the threshold, not a substitute
for one.

### Cost

The guard runs once per semantic lookup, on the incoming prompt.
Experiment 2 now measures it on every run. On the committed run it
averaged 61.3 µs per short corpus prompt and 13.7 ms per long-context
prompt (~18 KB, 4-5k tokens). The run was single-threaded on a shared,
heavily loaded host (load average ~17 on 8 cores). In a side-by-side
run in the same JVM on that host, long-context prompts cost about the
same as with the previous guard (33.6 ms vs 32.2 ms). Short prompts
cost roughly 1.5-3x more, which is still tens of microseconds. The
embedding call remains the dominant per-lookup cost.

Two implementation choices keep the guard's cost down:

- The date pattern rejects most positions with a one-character
  lookahead before it tries the case-insensitive month alternation.
- Spelled-out numbers are found with a single letter-run scan and map
  lookups. A regex alternation over every number word measured several
  times slower on long prompts.

### Measured effect, and its limits

These are the experiment 2 numbers
(`benchmark/results/experiment-2-guard-ablation.md`) at the 0.94
default, on the full corpus (76 near-duplicate pairs, 50 adversarial
pairs):

| Guard | Hit rate | False-hit rate |
|---|---|---|
| None | 14.47% (11/76) | 28.0% (14/50) |
| Numbers, dates, entities only (before) | 13.16% (10/76) | 8.0% (4/50) |
| Current | 14.47% (11/76) | **0.0% (0/50)** |

The old guard let four false hits through at 0.94:

- `pair-103`: aspirin "safe" vs "not safe" (negation)
- `pair-108`: "6:45pm" vs "6:45am" (time of day)
- `pair-118`: "60 minutes" vs "60 seconds" (unit)
- `pair-129`: "weekdays" vs "weekends" (opposition)

None of the four was a spelled-out number. "Two doses" vs "three doses"
(`pair-143`, similarity 0.91) sits below 0.94. The one recall loss at
0.94 (`pair-002`, "Write a Python function..." vs "Give me a Python
function...") was caused by a phantom `entity:write`, and that pair is
now a hit.

Across the whole sweep, the guarded false-hit rate is 0% from 0.81
upward. At 0.80, one pair still gets through: `pair-117` ("Room 204 is
reserved for the finance team" vs "...the legal team", similarity
0.80). The new tokens also reject three genuine paraphrases, all below
0.88 similarity. The old guard allowed the first two and already
rejected the third:

- `pair-012`: "causes of inflation" vs "causes inflation to rise"
- `pair-029`: "a higher salary" vs "a better salary"
- `pair-026`: "two-week" vs "14-day" (already a number mismatch)

**These corpus numbers are in-sample.** The new heuristics were written
after reading exactly which adversarial pairs got through, so 0 of 50
shows that the guard fits this corpus, not that it generalises.

For an out-of-sample signal, 20 new adversarial pairs and 10 new
paraphrase pairs were written after the guard was frozen. They were
checked against the guard alone, without embeddings, so this measures
what the guard can see rather than a false-hit rate:

| | Previous guard | Current guard |
|---|---|---|
| New adversarial pairs rejected | 1 of 20 | 6 of 20 |
| New paraphrase pairs allowed | 9 of 10 | 8 of 10 |

- **Caught**: "store the key" vs "never store the key", "requires" vs
  "doesn't require authentication", "five" vs "fifteen minutes", "2
  business days" vs "2 weeks", "before" vs "after boiling", "3.2 and
  later" vs "3.2 and earlier".
- **Missed**: faster/slower, read-only/writable, extended/shortened,
  penicillin/amoxicillin, left/right, cancelled/confirmed,
  mutex/semaphore, fixed/variable, tenant/landlord, under/over 12,
  adults/children, globally/locally, third/fourth floor,
  monthly/annually.
- **Paraphrases newly rejected**: "rendering twice" vs "render two
  times" (`num:2` on one side only), and "Why does my bread not rise?"
  vs "What stops bread dough from rising?" (negation on one side only).
  The previous guard rejected "Translate 'good morning' into Spanish"
  because of a phantom `entity:translate`.

These pairs were deliberately not used to tune the guard. Adding
"faster/slower" and "left/right" now would only move them in-sample.

### Known, accepted limitations

1. **Antonyms outside the lexicon pass.** The opposition list is short
   and generic on purpose. Domain opposites (fixed/variable rate,
   tenant/landlord, mutex/semaphore, read-only/writable) and
   comparatives other than higher/lower (such as faster/slower) are
   invisible to it, as the out-of-sample pairs above show.
2. **Common nouns that carry the fact pass.** "Finance team" vs "legal
   team", "penicillin" vs "amoxicillin", and "adults" vs "children"
   pass because none of these words is capitalized, numeric, or in the
   lexicon.
3. **Negation is presence-based.** "Not only fast but cheap" counts as
   negated, and so does a paraphrase that moves the negation into a
   verb ("not rise" vs "stops rising"). Both are false rejections, which
   is the safe direction.
4. **Ordinals are not numbers.** "Third floor" vs "fourth floor"
   passes. Converting "first" and "second" would collide with "60
   seconds" and "first, install...".
5. **A token on only one side is a mismatch.** A paraphrase that drops
   "higher", "rise", or a spelled-out number is rejected. On this corpus
   that costs recall below roughly 0.88 similarity, and nothing at 0.94.
6. **Entries written before this change keep their old fingerprint**
   until their TTL expires. The new tokens can only add mismatches, so
   an old entry is at worst as permissive as the old guard was.

Closing gaps 1 and 2 properly needs a model rather than a longer word
list: a small NER model, or a cross-encoder or NLI re-ranker over the
top candidate. ADR-007 currently rules out both.

## Bypass rules (F4.5)

`CacheBypassRule` skips cache lookup entirely, before any embedding or
guard work happens, when:

- `temperature > 0.3` - a request asking for creative/non-deterministic
  output has no business being served a memoised answer.
- Tools are present in the request - tool-calling responses are
  request-specific by nature.
- The `X-Aether-No-Cache` header is present - an explicit per-request
  opt-out, honoured unconditionally and checked first (it wins even if
  temperature or tools would also have triggered a bypass).

A bypass is recorded as its own `CacheDecision` (`BYPASS`), distinct
from `MISS`, so F4.9's per-request decision log can tell "the cache was
never consulted" apart from "it was consulted and found nothing."

## Namespace isolation (F4.4)

Every cache entry (both the Redis exact-match hot cache and the
Postgres/pgvector row) is scoped by a `namespace` string equal to the
requesting API key's id, or the fixed string `"anonymous"` for
unauthenticated requests (mirroring the M3 quota subsystem's same
choice for consistency). Every read and write path threads this
namespace through explicitly - there is no code path that queries or
stores without it - so cross-tenant leakage would require a namespace
value collision, not a missing check. Proven directly by
`CacheAdapterIntegrationTest.cacheEntriesNeverCrossNamespaces` and the
`@m4 @F4.4` Cucumber scenario, both against real Postgres.

## Chosen threshold: 0.94

Experiment 2 (`benchmark/results/experiment-2-guard-ablation.md`) has
the full 0.80-0.99 sweep with and without the guard. At 0.94 with the
guard active, the measured rates are **14.47% hit rate (11 of 76
near-duplicate pairs) and 0.0% false-hit rate (0 of 50 adversarial
pairs)**. Before the guard learned negation, spelled-out numbers, units
and oppositions, they were 13.16% and 8.0%.

An earlier revision of this document quoted a 14.5% guarded hit rate.
That figure came from `m4-threshold-sweep.md`, whose hit-rate column
was the *unguarded* rate even though its summary line said "with the
entity guard active". The guarded rate at the time was 13.16% (10 of
76), as BENCHMARKS.md said. `ThresholdSweepTest` now reports both
columns and uses the guarded rate for its operating point.

0.94 still does not clear AC3 (hit rate >= 35%). On this corpus the
guarded false-hit rate is now 0% at every threshold from 0.81 up, so
the sweep alone would favour a much lower threshold: 0.90 measures a
43.4% hit rate and 0.88 measures 59.2%, both with 0 of 50 false hits.
The default is deliberately **not** lowered on that basis, for two
reasons:

- The 0% is in-sample.
- The out-of-sample check above shows the guard missing 14 of 20 new
  adversarial pairs, and at a lower threshold more of those would clear
  the similarity gate.

Lowering the default should wait for a held-out adversarial set
measured with embeddings, not just with the guard. For the same reason,
`m4-threshold-sweep.md`'s automatically chosen operating point (now
0.85: 73.7% hit rate, 0 of 50 false hits) is reported as the sweep's
in-sample optimum, not adopted as the default.

0.94 is also the global default (`aether.cache.default-threshold`). A
route can set its own `cache.threshold` in routing.yaml. A request can
ask for a stricter point on the same measured curve with the
`X-Aether-Cache-Threshold` header (PRD section 13.1), but never a looser
one, because anonymous callers share a cache namespace (see [Routing
policy](./routing-policy.md)). The shipped example configs
(`routing.yaml`, `routing.docker.yaml`) set `cache.semantic: false`, so
their routes cache exact matches only, and semantic matching is an
explicit per-route opt-in. Given the out-of-sample result above, exact
matching remains the right default.

## Fail-open (ADR-005)

Unlike quota (fail-closed, Phase 06), a cache subsystem outage
(Redis unreachable, Postgres unreachable, or the embedding model
throwing) degrades to `CacheDecision.Miss` - the request still gets
served by the provider, just without a cache hit. `CacheAdapter` wraps
every lookup and store in a catch that swallows the exception rather
than propagating it; this is the deliberate asymmetry the PRD calls out
explicitly (a cache should never be able to turn an outage of its own
into a user-visible error).
