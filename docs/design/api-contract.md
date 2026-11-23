---
title: "API contract"
---

Covers every endpoint, header, and error envelope shape the gateway
exposes. The mock provider speaks the same OpenAI wire format on its own
side, one layer further out, so the gateway can be exercised end to end
without a real provider. For worked examples, see the
[Usage guide](../usage.md).

## Authentication

Gateway requests (`/v1/*`) carry `Authorization: Bearer <key>`. Gateway
keys are `aeth_...`-prefixed tenant keys (F9.1: stored as salted hashes,
`key_prefix` is the only plaintext fragment retained for display). A
request without a valid key is rejected with `401` unless the operator
sets `AETHER_SECURITY_ALLOW_ANONYMOUS=true`.

Admin requests (`/admin/*`) carry `X-Aether-Admin-Key: <key>` instead, a
separate key space from gateway API keys (F8.5). Admin auth is
fail-closed: if no admin key is configured, every admin request is
rejected.

## Gateway endpoints (OpenAI-compatible)

### `POST /v1/chat/completions`

Request body: OpenAI chat completions shape (`model`, `messages`,
standard sampling parameters, `tools` / `tool_choice` for tool calling)
plus one gateway-specific optional field, `variables`, used to fill in a
prompt referenced through `X-Aether-Prompt`. `model` is a route alias
from `routing.yaml`.

`stream: true` returns `text/event-stream`, chunk-relayed with no
intermediate buffering (F1.3); a client disconnect cancels the upstream
call. `stream: false` (or omitted) returns a single JSON response.
Tool calls come back in OpenAI's `tool_calls` shape, streamed or not.

A stream fails over like a non-streaming request until its first chunk
(see [Routing policy](./routing-policy.md)), so its response status and
`X-Aether-Provider` / `X-Aether-Attempts` / `X-Aether-Failover-Chain`
headers are only sent once a provider has produced a chunk or every
attempt has failed. A failure before the first chunk is answered with the
usual error envelope (`502 no_healthy_provider` when every chain member
failed); a failure after it ends the stream with a final
`data: {"error": {...}}` event and no `[DONE]`. Token usage for a stream
is the usage the provider reports at the end of it.

### `GET /v1/models`

Returns models aggregated across configured providers. Each entry
includes a `status` field, `"healthy"` or `"degraded"`, reflecting
current provider adapter/breaker health.

### `POST /v1/embeddings`

OpenAI embeddings shape (`model`, `input`, `encoding_format`,
`dimensions`), routed through the same authentication, quota and
failover chain as chat. Embeddings are never cached. Responses carry
`X-Aether-Provider`, `X-Aether-Attempts`, `X-Aether-Cost-USD` and
`X-Aether-Quota-Remaining`. See the
[Usage guide](../usage.md) for the supported providers.

## Custom request headers

| Header | Effect |
|---|---|
| `X-Aether-No-Cache` | Skip the cache for this request: no lookup and no write-back. Presence is what counts, whatever the value |
| `X-Aether-Cache-Threshold` | Per-request stricter similarity threshold (between the route's threshold and 1.0, else `400 invalid_request`) |
| `X-Aether-Prompt` | `name@alias` or `name@version` from the prompt registry |

## Response headers

| Header | Meaning |
|---|---|
| `X-Aether-Cache` | `MISS` \| `EXACT_HIT` \| `SEMANTIC_HIT` \| `BYPASS` |
| `X-Aether-Similarity` | Cosine score when semantically hit |
| `X-Aether-Provider` | Provider that actually served the request |
| `X-Aether-Attempts` | Tries made on the provider that answered (retries included) |
| `X-Aether-Failover-Chain` | Every provider tried, in order, comma-separated (absent when there is nothing to report) |
| `X-Aether-Cost-USD` | Cost of this request; `0` for a cache hit, absent on a streamed miss |
| `X-Aether-Quota-Remaining` | Tokens remaining this budget period |
| `X-Aether-Prompt-Version` | Prompt version that served the request, when one was referenced |

## Error envelope

Error responses follow the RFC 9457 problem-details shape:

```json
{
  "type": "https://aether.dev/problems/quota_exceeded",
  "title": "budget_exceeded",
  "status": 429,
  "detail": "Quota exceeded: budget_exceeded",
  "instance": "/v1/chat/completions"
}
```

`/v1/embeddings` errors keep these fields and add OpenAI's `error` object
(`message`, `type`, `param`, `code`), so SDKs surface a real message.

| Status | Condition |
|---|---|
| 400 | Malformed request (empty `messages`, unsupported `role`, invalid `X-Aether-Cache-Threshold`, prompt variable validation failure) |
| 401 | Missing or invalid API key (gateway) or admin key (admin endpoints) |
| 404 | Reference to a prompt, version or alias that does not exist |
| 429 | Rate limit or budget exceeded |
| 502 | Every provider in the requested model's chain is currently unavailable |
| 504 | Gateway's own timeout budget exceeded waiting on an upstream provider |

A per-key 429 has `type` `.../quota_exceeded` and names the reason in
`title`: `rps_exceeded` or `concurrency_exceeded` (retry shortly),
`budget_exceeded` (monthly budget, will keep failing until the next
period) or `quota_store_unavailable` (quota is fail-closed when Redis is
unreachable). It also carries `X-Aether-Quota-Remaining`. An anonymous
request over its per-IP limit gets `type` `.../ip_rate_limited`.

## Admin endpoints

```
POST   /admin/api-keys
GET    /admin/api-keys
PATCH  /admin/api-keys/{id}
DELETE /admin/api-keys/{id}

GET    /admin/routes
POST   /admin/routes/reload

POST   /admin/cost-model/reload

GET    /admin/providers/health
GET    /admin/providers/{name}/breaker

GET    /admin/cache/stats
DELETE /admin/cache?namespace=&prefix=

GET    /admin/usage?from=&to=&groupBy=key|provider|model|route
GET    /admin/requests

POST   /admin/prompts
POST   /admin/prompts/{name}/versions
PUT    /admin/prompts/{name}/aliases/{alias}
```

The operator console (`console/`) is a UI over these same endpoints.
