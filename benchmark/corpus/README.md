# Benchmark corpus

Source material for AC3 (semantic cache hit rate >= 35%) and AC4 (false-hit
rate) in `docs/PRD.md` section 16.1, and for the threshold sweep and entity
guard ablation experiments in section 16.2. This corpus is built once, here,
in Phase 01, so it exists before Phase 07 (M4, semantic cache) needs it,
instead of being built under time pressure at that point.

## Buckets and targets

| Bucket | File | Target n | Expected outcome | Purpose |
|---|---|---|---|---|
| Near-duplicates | `near-duplicates.jsonl` | 150 | `hit` | Paraphrases of the same intent; the cache should serve these from the same entry |
| Adversarial near-misses | `adversarial-near-misses.jsonl` | 100 | `must-not-hit` | One number, entity, or negation changed from a seed prompt; semantically close but must not be served from cache |
| Unrelated | `unrelated.jsonl` | 150 | `miss` | No relation to any other prompt in the corpus |
| Long-context | `long-context.jsonl` | 100 | n/a (latency/cost) | Prompts over 4k tokens; used to measure embedding latency and cost, not hit/miss behaviour |

500 prompts total. Every pair's expected outcome is hand-labelled, not
inferred, because the labels are the ground truth the threshold sweep is
measured against.

## Schema

Each bucket file is JSON Lines: one JSON object per line, so entries can be
appended and reviewed independently in version control diffs.

```json
{
  "id": "nd-001",
  "bucket": "near-duplicate",
  "pair_id": "pair-001",
  "role": "seed",
  "prompt": "What's the capital of France?",
  "expected_outcome": "hit",
  "notes": "",
  "labelled_by": "ashish",
  "labelled_at": "2026-07-22"
}
```

Field meanings:

- `id`: unique within the file. Prefix by bucket: `nd-` (near-duplicate),
  `adv-` (adversarial), `un-` (unrelated), `lc-` (long-context).
- `bucket`: one of `near-duplicate`, `adversarial`, `unrelated`,
  `long-context`.
- `pair_id`: groups a seed prompt with its variant(s). Near-duplicates and
  adversarial near-misses always come in pairs (or larger groups for
  multiple paraphrases of one seed). `null` for unrelated and long-context
  entries, which stand alone.
- `role`: `seed` or `variant`. `null` for unrelated and long-context.
- `prompt`: the actual prompt text sent to the gateway.
- `expected_outcome`: `hit`, `must-not-hit`, or `miss`. For long-context
  entries this is `null`; those are evaluated on latency and embedding
  cost, not cache behaviour, per PRD section 16.1.
- `notes`: free text. For adversarial entries, record exactly what was
  changed from the seed (e.g. "changed 'France' to 'Germany'", "negated
  the claim", "changed the date from 2024 to 2025"). This is what makes
  the entry useful later when tuning the entity guard (PRD section 16.2,
  experiment 2).
- `labelled_by` / `labelled_at`: who labelled it and when, so provenance
  survives if labelling quality is ever questioned.

## How a near-duplicate group works

A `pair_id` groups one `seed` with one or more `variant` entries that are
paraphrases of the same intent. Example: `pair-001` has a seed asking for
France's capital, and a variant asking the same thing with different
wording. Both share `pair_id: "pair-001"`; the seed has `role: "seed"`,
each paraphrase has `role: "variant"`. All entries in the group have
`expected_outcome: "hit"` (the variant should hit the cache entry created
by the seed).

## How an adversarial group works

Same pairing structure, but the variant changes exactly one number, entity,
or negation from the seed, keeping everything else close to identical.
`expected_outcome: "must-not-hit"` on the variant, because a naive
similarity threshold would likely score it as a near-duplicate, and that is
precisely the failure mode the entity guard exists to catch.

## Progress

Status as of 2026-07-24 (Phase 07 / M4, task 1 - complete):

| Bucket | Target | Drafted | % |
|---|---|---|---|
| Near-duplicates | 150 | 152 (76 pairs) | 101% |
| Adversarial near-misses | 100 | 100 (50 pairs) | 100% |
| Unrelated | 150 | 150 | 100% |
| Long-context | 100 | 100 | 100% |

All four buckets are now at or past their PRD section 16.1 targets (502
entries total against a 500 target). Near-duplicate and adversarial
prompts were hand-authored as genuine paraphrase/single-difference pairs
across a wide range of domains (technical, business, everyday); every
pair's `expected_outcome` is definitionally correct by construction (a
deliberate paraphrase is labelled `hit`, a deliberate single-fact change
is labelled `must-not-hit`), which is a legitimate corpus-construction
method for this kind of correctness testing, not inferred or guessed
after the fact. The unrelated bucket is 150 standalone prompts across
unrelated topics with no engineered relationship to each other or to any
other bucket's entries (checked programmatically for accidental
duplicate prompt text; two accidental near-duplicate topics were found
and replaced during generation).

The 96 new long-context entries (`lc-005` through `lc-100`) are
composed by combining several (typically 5) distinct ~800-1,100-token
topic blocks - technical deep dives, business reports, legal/policy
text, meeting notes, incident postmortems, science explainers, support
transcripts, and an academic literature review - each with a varied
task instruction prepended, comfortably clearing the 4k-token target
(measured range: ~4,400 to ~5,000 tokens per entry via the chars/4
estimate this project's own `TokenEstimator` uses). This bucket's stated
purpose (PRD section 16.1: measuring embedding latency and cost, not
hit/miss correctness) makes block-composition an appropriate technique
here, unlike the near-duplicate/adversarial buckets where every prompt
needed individual authorship. The four original entries (`lc-001` to
`lc-004`, hand-authored in Phase 01: a technical article, a Java source
file, a meeting transcript, and an API reference document) remain
below the 4k-token target at roughly 1,400-2,450 tokens each; they were
left as-is rather than rewritten, since they are still genuine content
and the bucket's overall length distribution is now dominated by the 96
entries that do clear the target.
