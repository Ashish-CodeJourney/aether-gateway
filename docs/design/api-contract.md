---
title: "API contract"
---

Covers every endpoint, header, and error envelope shape from PRD section
13. This is the contract Phase 03 (M0) through Phase 10 (M7) implement
against, and the contract Phase 03's mock provider must speak on its own
side (mock provider speaks the same OpenAI wire format the gateway's
`/v1/chat/completions` speaks, one layer further out).

## Authentication

Every gateway (`/v1/*`) and admin (`/admin/*`) request carries
`Authorization: Bearer <key>`. Gateway keys are `aeth_...`-prefixed
tenant keys (F9.1: stored as salted hashes, `key_prefix` is the only
plaintext fragment retained for display). Admin auth is a separate key
space from gateway API keys (F8.5); admin endpoints reject a gateway key
and vice versa.

## Gateway endpoints (OpenAI-compatible)

### `POST /v1/chat/completions`

Request body: OpenAI chat completions shape (`model`, `messages`,
standard sampling parameters) plus two gateway-specific optional fields:

- `routing_policy`: `"cost_optimized"` | `"latency_optimized"` | a
  specific provider name, to bypass automatic routing (introduced Phase
  05).
- `cache_bypass`: boolean, skips semantic cache lookup entirely
  (introduced Phase 07; accepted and ignored before then).

`stream: true` returns `text/event-stream`, chunk-relayed with no
intermediate buffering (F1.3, Phase 04). `stream: false` (or omitted)
returns a single JSON response (Phase 03).

Introduced by phase: non-streaming happy path and basic error envelope,
Phase 03 (M0). Streaming, cancellation, per-chunk relay, Phase 04 (M1).
Routing headers and failover, Phase 05 (M2). Quota headers and 429s,
Phase 06 (M3). Cache headers, Phase 07 (M4). Cost header backed by a real
cost model, Phase 08 (M5).

### `GET /v1/models`

Returns models aggregated across configured providers. Introduced Phase
04 (M1), mock-provider only; real providers added Phase 12 (M9). Each
entry includes a `status` field, `"healthy"` or `"degraded"`, reflecting
current provider adapter/breaker health (degraded status meaningful from
Phase 05 onward).

### `POST /v1/embeddings`

Passthrough to the configured embedding-capable provider. P2 priority
(F1.8); deferred, earliest Phase 07, only if time allows.

## Custom request headers

| Header | Effect | Introduced |
|---|---|---|
| `X-Aether-Route` | Force a specific route alias, bypassing automatic model-based resolution | Phase 05 |
| `X-Aether-No-Cache` | Bypass cache for this request | Phase 07 |
| `X-Aether-Cache-Threshold` | Per-request similarity override | Phase 07 |
| `X-Aether-Prompt` | `name@alias` or `name@version` from the prompt registry | Phase 10 |

## Response headers

Present on every gateway response once the corresponding phase lands:

| Header | Meaning | Introduced |
|---|---|---|
| `X-Aether-Cache` | `MISS` \| `EXACT_HIT` \| `SEMANTIC_HIT` \| `BYPASS` | Phase 07 |
| `X-Aether-Similarity` | Cosine score when semantically hit | Phase 07 |
| `X-Aether-Provider` | Provider that actually served the request | Phase 05 |
| `X-Aether-Attempts` | Failover attempts made | Phase 05 |
| `X-Aether-Cost-USD` | Estimated cost of this request | Phase 08 |
| `X-Aether-Quota-Remaining` | Tokens remaining this budget period | Phase 06 |

## Error envelope

All error responses follow RFC 9457 problem-details shape:

```json
{
  "type": "https://aether.dev/problems/quota-exceeded",
  "title": "Monthly token budget exceeded",
  "status": 429,
  "detail": "Key aeth_7f3a has consumed 1,000,000 of 1,000,000 monthly tokens.",
  "instance": "/v1/chat/completions"
}
```

| Status | Condition | Introduced |
|---|---|---|
| 400 | Malformed request body (empty `messages`, unsupported `role`) | Phase 03 |
| 401 | Missing or invalid API key | Phase 03 (basic), hardened Phase 06 |
| 403 | Valid key not authorized for the requested model | Phase 10 |
| 404 | Reference to a non-existent `model_id` / `entry_id` (admin endpoints) | Phase 10 |
| 429 | Rate limit or budget exceeded; always includes `Retry-After` and `X-Aether-Quota-*` headers | Phase 06 |
| 502 | Every provider in the requested model's chain is currently unavailable | Phase 05 |
| 504 | Gateway's own timeout budget exceeded waiting on an upstream provider | Phase 05 |

429 responses distinguish `rate-limit-exceeded` (RPS token bucket, retry
shortly) from `budget-exceeded` (monthly budget, will keep failing until
next period) via the `type` field, since clients should treat these
differently.

## Admin endpoints

```
POST   /admin/api-keys
GET    /admin/api-keys
PATCH  /admin/api-keys/{id}
DELETE /admin/api-keys/{id}

GET    /admin/routes
POST   /admin/routes/reload

GET    /admin/providers/health
GET    /admin/providers/{name}/breaker

GET    /admin/cache/stats
DELETE /admin/cache?namespace=&prefix=

GET    /admin/usage?from=&to=&groupBy=key|provider|model|route

POST   /admin/prompts
POST   /admin/prompts/{name}/versions
PUT    /admin/prompts/{name}/aliases/{alias}
```

Full CRUD (`/admin/api-keys/*`, prompt registry, usage query) is Phase
10. Two endpoints are pulled forward because earlier milestones need
them to prove their own exit criteria: `POST /admin/routes/reload` (M2,
hot-reload) and `GET /admin/providers/{name}/breaker` (M2, breaker state
observability, F3.8).
