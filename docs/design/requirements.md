# Requirements index

Comments and tests throughout this codebase cite requirement IDs
(`F4.5`), acceptance criteria (`AC6`), and milestones (`Phase 07 (M4)`).
Those identifiers come from a private planning document that is not part
of this repository, which left every reference here unresolvable from
the outside. This page closes that gap.

**What this is:** each identifier, with the requirement stated in plain
English, and where the implementation and its proof live. It is
reconstructed from the code and the tests rather than copied from the
original document - the running system and its acceptance suite are the
public source of truth, and where the two could ever disagree, the tests
win.

**Reading the citations:** a comment citing `F4.5` names the requirement
below. One citing `PRD section 12.2` names a section of that private
document - the requirement it carried is restated here, so the section
number is provenance rather than something you need to go and read. One
citing `Phase 07 (M4)` names the milestone in the table below.

Acceptance criteria AC1-AC9 are measured, not described, so they live
with their data in [`BENCHMARKS.md`](https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/BENCHMARKS.md)
and `benchmark/results/ac-scorecard.md`.

## Milestones

Each milestone is a working slice, gated by its own Cucumber feature
file in `gateway-acceptance-tests/src/test/resources/features/`.

| Milestone | Scope | Acceptance feature |
|---|---|---|
| M0 | OpenAI-shaped chat completions forwarded to a provider | `m0_basic_forwarding.feature` |
| M1 | SSE streaming with client-disconnect cancellation | `m1_streaming.feature` |
| M2 | Routing policy, provider chains, failover, hot reload | `m2_routing_and_resilience.feature` |
| M3 | API keys, quotas, budgets, rate limiting | `m3_quotas_and_rate_limiting.feature` |
| M4 | Two-tier cache: exact match plus semantic | `m4_semantic_cache.feature` |
| M5 | Metrics, request log, cost accounting | `m5_observability.feature` |
| M6 | Benchmarking harness and the AC scorecard | (no feature file - see `BENCHMARKS.md`) |
| M7 | Prompt registry, admin API, operator console | `m7_prompt_registry.feature` |
| M8 | Kubernetes deployment and zero-downtime rolling updates | (no feature file - see [Kubernetes deployment](./kubernetes-deployment.md)) |
| M9 | Real provider adapters and security hardening | `m9_ssrf_protection.feature` |

## F1 - Proxy surface

| ID | Requirement | Where |
|---|---|---|
| F1.1 | `POST /v1/chat/completions` accepts and returns the OpenAI wire shape unchanged, so an existing SDK works by changing `baseUrl` alone | `ChatCompletionController`, `ChatCompletionDtoMapper` |
| F1.2 | Streaming requests relay provider chunks as SSE | `ChatCompletionController#streamingResponse` |
| F1.3 | A stream terminates with `data: [DONE]` | same |
| F1.4 | A client disconnecting mid-stream cancels the upstream provider call, so tokens stop being paid for | Reactor cancellation through the Flux chain ([ADR-002](../adr/002-webflux-streaming-mvc-virtual-threads-elsewhere.md)) |
| F1.5 | A cancelled stream still records the usage it accrued before cancelling | `ChatCompletionController`, chunk-count fallback |
| F1.6 | `GET /v1/models` lists models from the loaded routing policy, without querying providers | `ModelsController` |
| F1.8 | `POST /v1/embeddings` - **not built**, deferred | see [API contract](./api-contract.md) |

## F2 - Routing

| ID | Requirement | Where |
|---|---|---|
| F2.1 | Every provider is reached through one `ProviderAdapter` port | `gateway-core` port, `gateway-providers` implementations |
| F2.2 | Adapters normalise each provider's own wire format to the domain model | `OllamaAdapter`, `GeminiAdapter`, `AnthropicAdapter`, `OpenAiCompatibleAdapter` |
| F2.3 | `routing.yaml` maps a model alias to an ordered provider chain | `RouteConfig`, `routing.yaml` |
| F2.4 | Predicate-based routing (API-key tag, request size) - **not built**; matching is by model alias only | - |
| F2.5 | A chain is walked in its configured attempt order | `Router` |
| F2.7 | `POST /admin/routes/reload` applies a changed `routing.yaml` with no restart | `RoutesController`, `m2_routing_and_resilience.feature` |

## F3 - Resilience

| ID | Requirement | Where |
|---|---|---|
| F3.1 | A circuit breaker is keyed per provider and model | `gateway-router`, Resilience4j |
| F3.2 | An unhealthy provider fails over to the next chain member | `m2_routing_and_resilience.feature`, AC6 |
| F3.3 | Retries use exponential backoff with jitter | `gateway-router` |
| F3.4 | A bulkhead per provider caps concurrent in-flight calls | `gateway-router` |
| F3.5 | Terminal failures (client errors) return immediately - no retry, no failover | `gateway-router` |
| F3.6 | `Retry-After` from a provider is honoured | `gateway-router` |
| F3.7 | One request-level timeout budget spans every attempt, failovers included | `gateway-router` |
| F3.8 | Breaker state is exported as a metric for the dashboard timeline | `BreakerMetricsPoller` |
| F3.9 | Breaker state is local per replica, with a Redis advisory hint rather than shared consensus | [ADR-004](../adr/004-circuit-breaker-state-local-with-redis-advisory-hint.md) |

## F4 - Caching

| ID | Requirement | Where |
|---|---|---|
| F4.1 | A cache key covers the canonicalised prompt, model, and request parameters | `cache_entry` schema, `gateway-cache` |
| F4.2 | A semantic hit requires cosine similarity above a configured threshold | `PgVectorCacheStore`, `m4_semantic_cache.feature` |
| F4.3 | Embeddings are generated in-process via ONNX, no network call on the request path | `gateway-cache`, [ADR-007](../adr/007-spring-ai-scope-embeddings-only.md) |
| F4.4 | Cache entries never cross namespaces - one API key can never read another's | `m4_semantic_cache.feature` |
| F4.5 | An entity/numeric guard rejects near-misses that similarity alone would accept | `EntityNumericGuard`, [cache correctness](./cache-correctness.md) |
| F4.6 | Only a genuine miss with a successful completion is written to cache - never a bypass, never an error | `CacheAdapter` |
| F4.7 | Per-route cache config, plus manual invalidation by model prefix | `RouteCacheConfig`, `CacheAdminController` |
| F4.8 | A cache hit for a streaming request is replayed as SSE, not returned whole | `ChatCompletionController#cacheHitResponse` |
| F4.9 | A cache hit records the cost it avoided, not zero | `saved_usd` in `request_log` |

## F5 - Quotas and rate limiting

| ID | Requirement | Where |
|---|---|---|
| F5.1 | Per-key requests-per-second token bucket | `token_bucket.lua` |
| F5.2 | Per-key monthly token budget | `budget_reserve.lua` |
| F5.3 | Per-key concurrency cap | `concurrency_acquire.lua` |
| F5.4 | A soft warning threshold, distinct from hard rejection | `gateway-quota` |
| F5.5 | Under concurrency the limit is exact - no over-issue and no under-issue | `RedisQuotaAdapterConcurrencyIntegrationTest`, AC8 |
| F5.6 | Output tokens are unknown up front, so budget is reserved pessimistically and reconciled against the real count afterwards | `budget_reconcile.lua`, [ADR-006](../adr/006-reservation-and-reconciliation-for-token-quotas.md) |

## F6 - Observability

| ID | Requirement | Where |
|---|---|---|
| F6.1 | Micrometer metrics on requests, latency, tokens, cost, breaker state, in-flight streams | `MicrometerMetricsAdapter` |
| F6.2 | Every request is logged asynchronously, never blocking the request path | `JdbcRequestLogWriter` |
| F6.3 | Per-provider, per-model pricing from an external hot-reloadable `cost-model.yaml` | `CostModelRepository` |
| F6.4 | Cost and avoided cost are attributed per request | `request_log.cost_usd` / `saved_usd` |
| F6.5 | Distributed tracing via Micrometer's OpenTelemetry bridge | `gateway-proxy` build config |
| F6.6 | Grafana dashboard and Prometheus datasource ship as committed provisioning config | `docker/grafana/provisioning/` |

## F7 - Prompt registry

| ID | Requirement | Where |
|---|---|---|
| F7.1 | Prompts are stored centrally, versioned, immutable once published | `gateway-registry`, `V4__prompt_registry.sql` |
| F7.2 | The resolved prompt version is returned as a response header | `X-Aether-Prompt-Version` |
| F7.3 | Prompts and versions are managed through the admin API | `PromptAdminController` |
| F7.4 | Repointing an alias takes effect on the very next request, no restart | `m7_prompt_registry.feature` |
| F7.6 | `{{placeholder}}` substitution with strict variable validation | `PromptTemplateRenderer` |

## F8 - Admin API

| ID | Requirement | Where |
|---|---|---|
| F8.1 | API key CRUD | `ApiKeysAdminController`, `V1__api_key.sql` |
| F8.2 | Read-only view of the loaded routing policy | `RoutesController` |
| F8.3 | Cache statistics per namespace | `CacheAdminController` |
| F8.4 | Usage aggregates grouped by key, provider, model, or route | `UsageAdminController` |
| F8.5 | Admin auth is fail-closed and separate from gateway API keys - no key configured means every admin request is refused | `AdminAuthFilter`, `m7_prompt_registry.feature` |

## F9 - Security hardening

| ID | Requirement | Where |
|---|---|---|
| F9.1 | API keys are stored as salted hashes, never plaintext | `ApiKeyHasher` |
| F9.2 | `routing.yaml` names the environment variable holding a provider credential, never the credential - the file stays safe to commit | `routing.yaml` |
| F9.3 | Provider base URLs are validated against private and loopback address ranges (SSRF) | `ProviderBaseUrlValidator`, `m9_ssrf_protection.feature` |
| F9.4 | Malformed requests surface as 4xx with an RFC 9457 body, not 500 | `ChatCompletionController` |
| F9.5 | Anonymous requests, where enabled, are rate-limited per source IP | `IpRateLimitPort`. Anonymous access is off by default - see [Usage](../usage.md#authentication) |

## F13

| ID | Requirement | Where |
|---|---|---|
| F13.1 | `X-Aether-Cache-Threshold` overrides the similarity threshold for one request | `ChatCompletionController` |

## Known gaps

Requirements referenced somewhere in the codebase that are deliberately
unbuilt: **F1.8** (embeddings endpoint) and **F2.4** (predicate-based
routing). `AnthropicAdapter` is built and passes the shared provider
contract suite against recorded fixtures, but has never been run against
the live Anthropic API - it is unproven, not proven. Measured criteria that miss their target - AC3, AC4, AC5 - are
documented with their real numbers in the scorecard rather than quietly
dropped.
