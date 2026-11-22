---
title: "Routing policy (routing.yaml)"
---

Reference for the YAML schema `RoutingYamlParser` (gateway-router) reads,
per PRD section 7 (F2). Loaded once at startup and on every
`POST /admin/routes/reload` (F2.7), from the filesystem path configured
via `aether.routing.config-path` (env var `ROUTING_CONFIG_PATH`, default
`routing.yaml` relative to the working directory) — a real file on disk,
not a classpath resource, since a classpath resource baked into the jar
cannot be hot-reloaded.

## Shape

```yaml
providers:
  <provider-name>:
    baseUrl: <http base URL the ProviderAdapter for this name calls>

routes:
  - alias: <model name clients request, e.g. "mock" or "fast-chat">
    chain:
      - provider: <provider-name, must exist under providers:>
        model: <the model name to send to that provider>
        weight: <integer, optional>
      - provider: <provider-name>
        model: <model name>
        # weight omitted here: this entry is a strict, order-only fallback
    cache:                # optional; absent means caching is off for this route
      enabled: true
      semantic: false     # optional, default true; false = exact-match only
      threshold: 0.94     # optional; defaults to aether.cache.default-threshold
      ttl: 6h             # optional, default 6h; s/m/h/d suffixes or ISO-8601
```

## Semantics

- **`providers`**: every name referenced by any route's `chain` must have
  an entry here. `type` selects which adapter in `gateway-providers`
  constructs it, and defaults to the mock adapter when omitted:

  | `type` | Adapter | Notes |
  |---|---|---|
  | `ollama` | `OllamaAdapter` | Local serving; no auth by default |
  | `gemini` | `GeminiAdapter` | camelCase wire format, key as a `?key=` query param |
  | `anthropic` | `AnthropicAdapter` | Messages API. **Never run against the live API** - see the adapter's javadoc |
  | `openai`, `groq`, `openai-compatible` | `OpenAiCompatibleAdapter` | Anything speaking the OpenAI wire format, including LiteLLM, vLLM and TGI |
  | omitted / anything else | `MockProviderAdapter` | Backward-compatible default |

  `apiKeyEnvVar` names the environment variable holding the credential,
  never the credential itself (F9.2), so this file stays safe to commit
  with real providers configured.
- **`routes[].alias`**: the value clients pass as `model` in
  `POST /v1/chat/completions`. At this phase, alias resolution is exact
  match only; predicate-based routing (F2.4: API-key tag, request size,
  `X-Aether-Route` header) is loaded but only `X-Aether-Route` forcing a
  specific alias is wired end-to-end so far.
- **`routes[].chain`**: an ordered list of candidate provider/model pairs.
  Entries **with** a `weight` are weighted-randomly ordered relative to
  each other on every request (F2.5); entries **without** a `weight` are
  strict, order-preserved fallbacks, always tried after every weighted
  entry, regardless of their position in the YAML list.
- A chain member's circuit breaker is keyed by `provider:model` (F3.1),
  not by provider alone, so a bad model on an otherwise-healthy provider
  does not trip the breaker for that provider's other models.
- **`routes[].cache`** (F4.7): per-route response caching. Without a
  `cache:` block, or with `enabled: false`, every request for the alias
  goes to a provider.
  - **`semantic`** chooses how a request matches a stored response.
    `false` is **exact-only**: a response is reused only for an identical
    request (same messages and parameters), so it can never return one
    prompt's answer for a different prompt. An exact miss goes straight
    to the provider - no embedding is computed and no similarity search
    runs, and the stored entry carries no embedding at all, which also
    keeps it out of every other route's semantic search. `true` (the
    default when the key is absent, for backward compatibility) adds
    semantic matching on an exact miss: the prompt is embedded and the
    nearest stored prompts are accepted above `threshold`, subject to the
    entity/numeric guard (see [Semantic cache
    correctness](./cache-correctness.md)). Prefer `semantic: false` unless
    a route's answers can tolerate the semantic cache's measured
    false-hit rate.
  - **`threshold`** is the cosine-similarity floor for a semantic hit.
    The threshold actually applied is, in order of precedence: a valid
    `X-Aether-Cache-Threshold` request header, then this route's
    `threshold`, then the global `aether.cache.default-threshold` (env
    var `AETHER_CACHE_DEFAULT_THRESHOLD`, default `0.94`).
  - **`X-Aether-Cache-Threshold`** can only make matching *stricter*: it
    must be a number between the route's effective threshold (above) and
    `1.0` inclusive, or the request is rejected with `400
    invalid_request` before any quota is reserved. Anonymous callers
    share one cache namespace, so a caller allowed to loosen the
    threshold could be served - and on a miss, seed - loose matches that
    other callers then receive. The header is validated on every route,
    including exact-only and uncached ones, where a valid value is simply
    unused.
  - **`ttl`** bounds how long an entry is served.

## Example: the local dev / M2 default (`routing.yaml` at repo root)

```yaml
providers:
  mock-primary:
    baseUrl: http://localhost:8082
  mock-fallback:
    baseUrl: http://localhost:8083

routes:
  - alias: mock
    chain:
      - provider: mock-primary
        model: mock
        weight: 100
      - provider: mock-fallback
        model: mock
```

A request for `"model": "mock"` always tries `mock-primary` first
(the only weighted entry, so weighted-random selection is trivial with
one candidate); if `mock-primary`'s breaker is open or its calls exhaust
their retry budget, the request fails over to `mock-fallback` next, per
F3.2.

Streaming requests (`"stream": true`) walk the chain the same way, with
the same breakers, bulkheads, retries and timeout budget, but only up to
the stream's first chunk. A retryable failure before then (429, 5xx, or
an unreachable provider) moves on to the next member; a terminal one
(any other 4xx) is returned immediately. Once a chunk has been sent to
the client the stream is committed to that provider: a later failure
ends the stream with an `error` event rather than switching providers,
which would duplicate or corrupt output the client already has. A
provider's bulkhead slot is held for as long as its stream runs.

## Reload

`POST /admin/routes/reload` calls `RoutingPolicyRepository.reload()`,
which re-reads and re-parses the file and atomically swaps both the
route table and the constructed `ProviderAdapter` instances. In-flight
requests using the old policy snapshot are unaffected; new requests use
the reloaded policy immediately.
