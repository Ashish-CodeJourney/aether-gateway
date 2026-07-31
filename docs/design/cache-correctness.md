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

`EntityNumericGuard` (`gateway-core`) extracts three kinds of tokens
from a prompt - numbers, dates, and named entities - into a
"fingerprint" set, and a semantic hit is rejected outright, regardless
of similarity score, if the candidate's fingerprint doesn't exactly
match the stored entry's. This runs in `CacheAdapter.lookup` after the
threshold gate, for every candidate the threshold gate would otherwise
accept.

Extraction is a deliberate, in-process, no-network heuristic, not a
trained NER model:

- **Numbers**: regex-matched digit sequences (`-?\d+(?:\.\d+)?`).
- **Dates**: ISO (`2024-01-01`), slash (`1/5/2024`), and named-month
  (`January 5, 2024`) patterns.
- **Named entities**: capitalized-word runs, minus a stopword list of
  common sentence-initial words (interrogatives, articles, pronouns),
  so that "What is..." doesn't itself register as an entity while
  "France" does.

ADR-007 scopes Spring AI usage to the embedding model only; a real NER
model is out of scope for this project, and the guard is explicitly a
heuristic safety net layered on top of the threshold, not a substitute
for one.

### Known, accepted limitations (measured, not guessed)

The guard's scope is exactly "numbers, named entities, dates," per the
PRD's own wording, and nothing outside that scope is caught. The M4
threshold sweep (`benchmark/results/m4-threshold-sweep.md`) measured
this directly against the corpus rather than assuming it:

1. **Negation and polarity flips are invisible to the guard.**
   "The library is open" vs "the library is closed," "revenue increased"
   vs "revenue decreased," "expected" vs "not expected" - none of these
   change a number, entity, or date, so the guard has nothing to compare
   and cannot block them. This is the single largest contributor to the
   nonzero false-hit rate at every threshold measured.
2. **Spelled-out numbers are not extracted.** The number regex matches
   digit sequences only; "two doses" vs "three doses" does not register
   as a number-fingerprint mismatch the way "2 doses" vs "3 doses"
   would. Found directly while picking example pairs for the M4
   Cucumber scenarios (`gateway-acceptance-tests/.../m4_semantic_cache.
   feature`): the pair "The vaccine requires two doses." / "...three
   doses." measured 0.91 cosine similarity with the guard reporting the
   fingerprints as equal (i.e. it would incorrectly allow a hit), while
   digit-form equivalents like "The hotel has 120 rooms." / "...220
   rooms." are correctly caught.
3. **Common nouns that carry factual meaning aren't entities.** "on
   weekdays" vs "on weekends" changes the fact but neither word is
   capitalized or numeric, so the guard is blind to it. Also found while
   picking Cucumber example pairs (this exact pair was removed from the
   feature file for that reason and replaced with a number-based pair
   the guard does catch).

None of these are correctness bugs in the sense of behaving differently
than designed; they are the measured boundary of a guard whose scope was
deliberately kept narrow. Extending the guard to catch negation (a small
stopword-adjacent check) or spelled-out numbers (a word-to-number
lookup) are reasonable, scoped Phase 09 follow-ups, not required for
this phase's exit criterion, which only requires the false-hit rate to
be *measured and the threshold choice justified* - not zero.

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

See `benchmark/results/m4-threshold-sweep.md` for the full swept table
and per-threshold hit/false-hit numbers. Summary: 0.94 is the Pareto-best
threshold among those keeping the cache meaningfully useful (hit rate
>= 10%, a floor only to exclude degenerate near-zero-hit-rate points),
measuring a 14.5% hit rate and an 8.0% false-hit rate with the guard
active on the corpus as currently sized (76 near-duplicate pairs, 50
adversarial pairs). This does not yet clear AC3 (hit rate >= 35%) or
AC4 (false-hit rate `<= 5%`) simultaneously - expected and accepted per
this phase's plan, which only requires the numbers to be measured and
the choice justified, with the full 0.80-0.99-step sweep and further
tuning left to Phase 09.

Routing config (`routing.yaml`, `routing.docker.yaml`) sets this same
0.94 as the `mock` route's default `cache.threshold`, with a per-request
override available via the `X-Aether-Cache-Threshold` header (PRD
section 13.1) for callers who want a different point on the same
measured curve.

## Fail-open (ADR-005)

Unlike quota (fail-closed, Phase 06), a cache subsystem outage
(Redis unreachable, Postgres unreachable, or the embedding model
throwing) degrades to `CacheDecision.Miss` - the request still gets
served by the provider, just without a cache hit. `CacheAdapter` wraps
every lookup and store in a catch that swallows the exception rather
than propagating it; this is the deliberate asymmetry the PRD calls out
explicitly (a cache should never be able to turn an outage of its own
into a user-visible error).
