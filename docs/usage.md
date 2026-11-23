---
title: Usage Guide
sidebar:
  order: 3
---

## Making a request

`POST /v1/chat/completions` - the standard OpenAI chat completions shape, non-streaming or streaming:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "mock",
    "messages": [{"role": "user", "content": "hello"}],
    "stream": false
  }'
```

Set `"stream": true` for a Server-Sent Events response, identical in shape to OpenAI's streaming format (`data: {...}` chunks, terminated by `data: [DONE]`). Cancelling the client connection (closing the tab, aborting the fetch) propagates upstream and stops paying for tokens - this is measured, not assumed (see [WebFlux for streaming](./adr/002-webflux-streaming-mvc-virtual-threads-elsewhere.md)). A stream fails over to the next provider in the chain just like a non-streaming request, as long as the failure happens before its first chunk; after that it is committed to the provider that started it (see [Routing policy](./design/routing-policy.md)).

A stream is billed at the token usage the provider reports when it finishes (Anthropic, Gemini, Ollama and OpenAI-compatible providers all report it; the gateway asks OpenAI-compatible ones with `stream_options.include_usage` itself), and that usage is what the request log, the cost metrics and the API key's monthly budget see. Its headers go out before that usage exists, so a streamed response carries no `X-Aether-Cost-USD`. A stream that is cancelled or fails before the provider reports usage, or from a provider that reports none, is recorded at the gateway's pre-request estimate instead.

`GET /v1/models` lists the models configured across every route in `routing.yaml`.

## Tool calling

Send OpenAI-format `tools` and `tool_choice`, and replay assistant `tool_calls` and `role: "tool"` results in later turns, exactly as you would to OpenAI. Replies carry `tool_calls` with `finish_reason: "tool_calls"`, and streams carry `delta.tool_calls`. Requests that use tools are never cached. How each provider type handles them:

| Provider type | Tools | `tool_choice` |
|---|---|---|
| OpenAI-compatible (`openai`, `groq`, `openai-compatible`) | Sent unchanged | Sent unchanged |
| Anthropic | Translated to `tools` / `tool_use` / `tool_result` | `auto`, `none`, `required`, named function |
| Gemini | Translated to `functionDeclarations` / `functionCall` / `functionResponse` | `auto`→`AUTO`, `none`→`NONE`, `required`→`ANY`, named function→`ANY` restricted to that function |
| Ollama (native `/api/chat`) | Sent as-is; arguments converted between Ollama's JSON object and OpenAI's JSON string | `auto` (or omitted) sends the tools; `none` leaves them out; `required` or a named function returns `400 tool_choice_not_supported`, because Ollama can't force a tool call |

Gemini specifics:

- **Schemas.** Gemini accepts only an OpenAPI subset of JSON Schema, so each tool's `parameters` is converted. A type list like `["integer", "null"]` becomes `type: "integer"` plus `nullable: true`, `oneOf` becomes `anyOf`, and `const` becomes a one-value `enum`. Keywords Gemini doesn't support are dropped, including `$schema`, `additionalProperties`, `$ref`/`$defs`, `examples`, `allOf` and `not`. A sub-schema reached through `$ref` therefore loses its structure, so inline it if the model needs it. A function with no properties is declared without `parameters`, which is how Gemini says "no arguments".
- **Call ids.** Gemini usually returns no id with a function call, so the gateway generates `call_<n>_<function name>`, numbered within the reply. Gemini also needs a function result's name, which an OpenAI `tool` message doesn't carry, so the gateway looks it up from the assistant tool call with the same `tool_call_id` earlier in the conversation. A `tool` message whose id matches no earlier call is rejected with `400 invalid_request`.
- **Tool results** that are a JSON object are sent as-is. Any other result is wrapped as `{"output": "<result>"}`.
- **Thought signatures.** Gemini 3 models attach a `thoughtSignature` to function calls and expect it back. The OpenAI format has nowhere to carry it, so a replayed call is sent with Google's documented `skip_thought_signature_validator` placeholder instead. Google warns this can make the model's reasoning worse on multi-step tool use.
- A streamed function call arrives whole, so it's emitted as a single `delta.tool_calls` entry that carries the id, the name and the complete arguments.

Ollama returns tool call ids itself (0.24 does), and the gateway generates `call_<n>_<function name>` for older versions that don't. A `tool` result goes to Ollama with the `tool_name` taken from the matching earlier call. Tool calling needs a model that supports it, such as `llama3.1` or `qwen3`.

## Embeddings

`POST /v1/embeddings` is OpenAI's embeddings shape, so any OpenAI SDK's `embeddings.create(...)` works when pointed at the gateway:

```bash
curl -X POST http://localhost:8080/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AETHER_API_KEY" \
  -d '{"model": "mock", "input": ["the cat sat", "on the mat"]}'
```

```json
{
  "object": "list",
  "data": [
    {"object": "embedding", "index": 0, "embedding": [0.12, -0.03, ...]},
    {"object": "embedding", "index": 1, "embedding": [0.08, 0.41, ...]}
  ],
  "model": "mock",
  "usage": {"prompt_tokens": 4, "total_tokens": 4}
}
```

- `model` is a route alias from `routing.yaml`, exactly as for chat; each chain member's own `model` is what gets sent upstream, so point an embeddings route at embedding models.
- `input` is a string or an array of strings (up to 2048). Token-id arrays are rejected with `400`: a route can span providers with different tokenizers, so token ids would only ever mean something to one of them.
- `encoding_format` is `float` (default) or `base64` (little-endian float32 bytes, as OpenAI returns). `dimensions` is passed through to providers that support shortened embeddings.

Embeddings go through the same authentication, quota (the reservation is the estimated input tokens) and failover chain as chat. A provider with no embeddings API (Anthropic) is skipped and the next chain member is tried; if no member of the route supports embeddings, the response is a `400 embeddings_not_supported`. Supported providers: OpenAI-compatible (`openai`, `groq`, `openai-compatible` types), Ollama (`/api/embed`), Gemini (`batchEmbedContents`) and the mock provider (deterministic vectors, for tests). Ollama and Gemini don't always report token counts; usage then falls back to the gateway's own estimate.

Responses carry `X-Aether-Provider`, `X-Aether-Attempts` (every provider call made across the chain, retries included), `X-Aether-Cost-USD` and `X-Aether-Quota-Remaining`. Cost uses `cost-model.yaml` with an output price of `0`; an entry is matched by the upstream model first, then the route alias, and an unpriced model costs `0`. There is no `X-Aether-Cache`: embeddings are never cached, since an indexing job rarely asks for the same vector twice and the semantic cache is keyed on chat messages.

Errors keep the gateway's RFC 9457 fields and add OpenAI's `error` object (`message`, `type`, `param`, `code`), so SDKs surface a real message.

## Authentication

An `Authorization: Bearer <api-key>` header attaches the request to a real API key - its own rate limit, concurrency cap, and monthly token budget apply, and usage is attributed to it in the request log and cost dashboard.

Real API keys are created via the [admin API](#admin-api) and are stored as salted hashes, never plaintext.

A request with no `Authorization` header - or one that doesn't resolve to a real key - is **rejected with `401 unauthenticated` by default**. Such a request is unmetered, charged to no budget, and served using the operator's own provider credentials, so a gateway reachable beyond localhost would otherwise be an open proxy to whatever those credentials can buy. An unrecognised key is treated identically to no key at all, so the endpoint can't be used to probe which keys exist.

Setting `AETHER_SECURITY_ALLOW_ANONYMOUS=true` opts back in: anonymous requests are then served unmetered against any per-key budget, but rate-limited per source IP address so one anonymous client can't exhaust the gateway. The Docker Compose stack and the acceptance suite both enable it, since neither has real credentials to protect; the Kubernetes manifests deliberately leave it off.

## Response headers

Every response carries headers describing exactly what happened, useful for debugging and for building your own dashboards on top:

| Header | Meaning |
|---|---|
| `X-Aether-Cache` | `MISS`, `EXACT_HIT`, `SEMANTIC_HIT`, or `BYPASS` (the cache was not consulted: `X-Aether-No-Cache`, tools in the request, `temperature > 0.3`, or caching off for the route) |
| `X-Aether-Cost-USD` | Real cost for a miss, `0` for any cache hit; absent on a streamed miss, whose cost is only known once the stream ends |
| `X-Aether-Provider` | Which provider actually served this (useful for confirming failover happened) |
| `X-Aether-Attempts` | Tries made on the provider that answered (retries included) |
| `X-Aether-Failover-Chain` | Every provider tried, in order, e.g. `mock-primary,anthropic-main` |
| `X-Aether-Quota-Remaining` | Remaining monthly token budget for the attached API key |
| `X-Aether-Prompt-Version` | Present only when the request referenced a prompt (see below) - which version actually served it |

## Controlling the cache per request

- `X-Aether-No-Cache: true` - bypass the cache entirely for this one request: it is never answered from the cache, and its response is not written back either. The header's presence is what counts, whatever its value.
- `X-Aether-Cache-Threshold: 0.97` - require a *stricter* similarity for a semantic hit on this one request, without touching `routing.yaml`. The value must be between the route's configured threshold (its `cache.threshold`, else the global `AETHER_CACHE_DEFAULT_THRESHOLD`, default `0.94`) and `1.0`; anything looser, or not a number, is rejected with `400 invalid_request`. Clients can never loosen matching, because anonymous callers share one cache namespace.

Whether a route caches at all, and whether it matches semantically or only exactly, is per-route configuration: `cache.semantic: false` in `routing.yaml` makes a route exact-only (identical requests only, no embeddings). See [Routing policy](./design/routing-policy.md).

## The prompt registry

Create and version a named prompt via the [admin API](#admin-api), then reference it from a request instead of inlining the prompt text:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Aether-Prompt: support-reply@production" \
  -d '{"model": "mock", "messages": [], "variables": {"customer_name": "Alex"}}'
```

`support-reply@production` resolves the `production` alias of the `support-reply` prompt to whichever version it currently points at - repointing that alias via the admin API takes effect on the *very next* request, no gateway restart. The response carries `X-Aether-Prompt-Version` confirming which version actually served it.

## Admin API

Every `/admin/**` endpoint requires `X-Aether-Admin-Key: <key>` (fail-closed - a blank or missing configured key rejects every admin request). The key is set via `AETHER_ADMIN_API_KEY`; the operator console (`console/`) is the friendlier way to reach all of these.

| Endpoint | Purpose |
|---|---|
| `POST /admin/api-keys` · `GET /admin/api-keys` · `DELETE /admin/api-keys/{id}` | Create, list, revoke API keys |
| `POST /admin/prompts` · `POST /admin/prompts/{name}/versions` · `PUT /admin/prompts/{name}/aliases/{alias}` | Create a prompt, add a version, repoint an alias |
| `GET /admin/routes` · `POST /admin/routes/reload` | Inspect the live routing policy; hot-reload `routing.yaml` |
| `POST /admin/cost-model/reload` | Hot-reload `cost-model.yaml` (an invalid file is rejected with 400 and the old prices stay in force) |
| `GET /admin/providers/health` · `GET /admin/providers/{name}/breaker` | Provider health and circuit breaker state |
| `GET /admin/cache/stats` · `DELETE /admin/cache` | Cache hit-rate stats; invalidate the whole cache |
| `GET /admin/usage` | Aggregated token/cost usage |
| `GET /admin/requests` | Request log explorer (provider, cache outcome, cost, latency per request) |

## Observability

Grafana (`http://localhost:3000` under Docker Compose) ships with a pre-provisioned dashboard: request rate by route, p50/p95/p99 latency by provider, cache hit rate over time, cumulative spend vs. spend avoided, circuit breaker state timeline, in-flight streams, top API keys by cost, and error rate by provider. Prometheus (`http://localhost:9090`) scrapes `/actuator/prometheus` directly if you'd rather query the raw metrics yourself.

## Deploying

Docker Compose (above) is a complete, legitimate deployment target for a single host. For Kubernetes specifically - graceful rolling updates that don't break in-flight streams, autoscaling on the right signal, and the manifests themselves - see [Kubernetes deployment](/docs/design/kubernetes-deployment).
