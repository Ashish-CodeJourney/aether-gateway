---
title: "Cost model"
---

How Phase 08 (M5) computes `cost_usd` and `cache_savings_usd` per
request, and how the per-(provider, model) price table itself is
configured. Feeds directly into Phase 09 (M6)'s cost simulation
experiments.

## Price table: `cost-model.yaml`

External, flat YAML, one entry per (provider, model) pair, in input/output
price per 1,000,000 tokens:

```yaml
models:
  - provider: mock-primary
    model: mock
    inputPricePerMillion: 0.50
    outputPricePerMillion: 1.50
```

Loaded once at startup by `CostModelRepository`
(`gateway-observability`), matching `RoutingPolicyRepository`'s F2.7
hot-reload shape (PRD section 8's "config change requires no redeploy"
non-functional requirement): `reload()` re-reads the file from disk.
Unlike routing, there is currently no `POST /admin/cost-model/reload`
endpoint wired up to trigger it from outside the process - that is a
natural Phase 10 admin-API addition, not required for this phase's exit
criterion, so the mechanism exists but isn't yet exposed over HTTP.

Prices for `mock-primary`/`mock-fallback` are illustrative, chosen to be
easy to hand-verify ($0.50 input / $1.50 output per 1M tokens), since
there is no real invoice to match against for a mock provider. Real
provider prices (Groq, Gemini, etc.) get added to this same file once
Phase 12 (M9) wires up real providers - the price table's shape does not
need to change, only its contents.

Embedding models (`POST /v1/embeddings`) use the same entry shape with
`outputPricePerMillion: 0.0`, since an embedding has no completion
tokens; cost is `input_tokens * inputPricePerMillion / 1,000,000`. The
embeddings endpoint looks an entry up by the serving chain member's
upstream model first, then by the route alias, and reports `0` for a
model with no entry.

## `cost_usd`: the math

`CostCalculator.costUsd(pricing, inputTokens, outputTokens)`
(`gateway-core`, pure, no I/O) computes:

```
cost_usd = (inputTokens  / 1,000,000) * inputPricePerMillion
         + (outputTokens / 1,000,000) * outputPricePerMillion
```

Token counts come from whichever source is authoritative for that
response path:

- **Non-streaming, served by a real provider call**: the actual
  `prompt_tokens`/`completion_tokens` the provider reported
  (`ChatCompletionResponse.usage()`), the same usage figures already
  used for quota reconciliation (Phase 06/M3).
- **Streaming, served by a real provider call**: the usage the provider
  reports at the end of the stream, which each adapter puts on the
  stream's last chunk (`ProviderResponse.StreamChunk.usage()`):
  Anthropic's `message_start` input and `message_delta` output counts,
  the OpenAI-compatible final `usage` chunk (requested with
  `stream_options: {"include_usage": true}`), Gemini's
  `usageMetadata`, and Ollama's `prompt_eval_count`/`eval_count`. The
  same figures go to the request log, the metrics, and the quota
  reconciliation, so a streamed request is billed exactly like a
  non-streaming one. When no usage arrives - the client cancelled the
  stream (F1.5), it failed mid-way, or the provider simply reports
  none - the request's pessimistic pre-request estimate is used
  instead, all of it counted as output tokens, as before.
  The controller does not yet know which chain member served a stream,
  so a streamed request is priced at the route's primary member, the
  same stand-in a cache hit uses (below).
- **A cache hit (exact or semantic)**: `cost_usd` is always exactly
  `0` - nothing was paid, because no provider call happened. This is
  what makes `cache_savings_usd` meaningful as a genuine cost
  optimisation number rather than a notional one.

## `cache_savings_usd`: what a hit *would have* cost (F6.4)

Same `CostCalculator.costUsd(...)` function, applied to the cached
response's own recorded `usage` (prompt/completion tokens from when it
was originally generated and stored, per Phase 07/M4's cache write
path), for a cache hit specifically. It answers "what would this exact
response have cost if it had gone to the provider instead." A response
cached from a stream records the usage the provider reported for that
stream, or zero if it reported none.

**Attribution for a cache hit.** A cache hit, by definition, did not
call any provider, so there is no `servedByProvider` value the way a
real completion has one - the stored response DTO carries no provider
attribution (Phase 07/M4 never modelled that field, since the cache
itself doesn't care who originally generated a response, only that it
did). To still produce a meaningful dollar figure and a meaningful
`provider` tag on the `X-Aether-*` metrics/log for a hit, `cost_usd`/
`cache_savings_usd` use the price of the requested route's *primary*
chain member (the first entry in `routing.yaml`'s chain for that
route/model) as a defensible stand-in for "what this would have cost
had it gone to the provider" - not a guess at which specific provider
would have actually served it, but the natural default given no better
signal exists. This choice is implemented once, in
`ChatCompletionController.resolvePricing`, and used identically for
both the `X-Aether-Cost-USD` response header and the `request_log`/
metrics values, so the three surfaces never disagree with each other.

## Where these numbers surface

- **Response headers** (PRD section 13.1): `X-Aether-Cost-USD` on every
  response (`0` for a cache hit). `X-Aether-Provider` and
  `X-Aether-Attempts` also added in this phase, carrying the same
  `servedByProvider`/`attemptCount` attribution `ResilientRouter` now
  threads back through `ProviderResponse.Completion`/`ProviderError`
  (previously untracked entirely - Phase 05/M2 resolved failover
  correctly but never surfaced *which* provider won or how many
  attempts it took).
- **`request_log`** (F6.2): `cost_usd` and `saved_usd` columns, written
  asynchronously on every request regardless of outcome (quota-rejected,
  cache hit, provider success, provider failure, or a streamed request
  that completed/was cancelled/errored).
- **Micrometer metrics** (F6.1): `aether_gateway_cost_usd_total` and
  `aether_gateway_savings_usd_total` counters, tagged by
  `provider`/`model`/`api_key`, feeding the Grafana dashboard's
  "cumulative spend vs. spend avoided" and "top keys by cost" panels
  directly.

## Known limitations

- A streamed response that is cancelled, fails mid-way, or comes from
  a provider that reports no streaming usage is costed at the
  pre-request estimate rather than a measured count (see above).
- A streamed response is priced at the route's primary member, not
  necessarily the provider that served it, since that is not yet
  known for streams.
- A cache hit's cost/savings estimate is attributed to the route's
  primary provider's price, not the provider that actually generated
  the originally-cached response (not tracked). If a route's providers
  have meaningfully different prices for the same model, this
  understates or overstates savings depending on which provider
  actually served the original request. Acceptable for this phase;
  worth revisiting if per-provider price divergence becomes large
  enough to matter (e.g. once real, differently-priced providers are
  wired up in Phase 12/M9).
