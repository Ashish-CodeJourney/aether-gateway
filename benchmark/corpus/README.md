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

Status as of 2026-07-22 (Phase 01, task 7 - schema and initial draft, not
full labelling):

| Bucket | Target | Drafted | % |
|---|---|---|---|
| Near-duplicates | 150 | 5 (5 pairs) | 3% |
| Adversarial near-misses | 100 | 5 (5 pairs) | 5% |
| Unrelated | 150 | 8 | 5% |
| Long-context | 100 | 2 | 2% |

This is well short of the phase's 25%-per-bucket minimum (PRD-derived task
7 target in `docs/plan/01-requirements-and-planning.md`). The schema is
proven for near-duplicates, adversarial, and unrelated (each holds real,
hand-labelled prompts). The bulk of hand-labelling for all four buckets
remains open work. Continue filling in each bucket incrementally; do not
defer it all to Phase 07.

**Long-context is honestly incomplete, not just under target.** The two
entries in `long-context.jsonl` are structural placeholders (~60 tokens,
literal `[PLACEHOLDER: ...]` text) that prove the JSON schema parses, not
real long-context samples. Real entries need to come from actual long
documents already on hand (large code files, README-length docs, meeting
transcripts), pasted or referenced in, not hand-written from scratch. This
bucket needs real content before it can be called "started" in the sense
the other three buckets are.
