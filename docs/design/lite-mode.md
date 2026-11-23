---
title: "Lite mode"
---

Lite mode runs the whole gateway as **one container with no external
dependencies**: no Postgres, no Redis, no ONNX model download. It exists
for trying Aether out and for small deployments that need exactly one
gateway process. The decision and its trade-offs are recorded in
[ADR-010](/docs/adr/lite-mode).

**Lite mode is single-replica and non-durable.** API keys, prompts, the
request log and monthly budget counters live in process memory and are
gone after a restart. Run exactly one replica.

## Running it

```bash
# One container, nothing else:
docker run -p 8080:8080 \
  -e AETHER_LITE_BOOTSTRAP_API_KEY=aeth_try_me \
  -e OPENAI_API_KEY=sk-... \
  ashishvaghelaa/aether-gateway-proxy:lite

# Or build it from this repository:
docker compose -f docker-compose.lite.yml up --build   # or: make lite
```

`:lite` is published with each release; trunk builds are published as
`:edge-lite`.

The image sets `SPRING_PROFILES_ACTIVE=lite` and bundles a default
routing policy, [`aether-gateway/routing.lite.yaml`](https://github.com/Ashish-CodeJourney/aether-gateway/blob/trunk/aether-gateway/routing.lite.yaml),
at `/app/config/routing.yaml`:

| Model alias | Provider | Needs |
|---|---|---|
| `echo` | Answers in-process with `Echo: <your message>` | Nothing - no network, no key |
| `gpt` | OpenAI `gpt-4o-mini`, falling back to Gemini | `OPENAI_API_KEY` (and `GEMINI_API_KEY` for the fallback) |
| `gemini` | Gemini `gemini-flash-latest` | `GEMINI_API_KEY` |
| `claude` | Anthropic `claude-haiku-4-5` | `ANTHROPIC_API_KEY` |

To use your own policy, mount it over the default:
`-v ./my-routing.yaml:/app/config/routing.yaml:ro`.

```bash
curl -sS -D - http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer aeth_try_me" -H "Content-Type: application/json" \
  -d '{"model":"echo","messages":[{"role":"user","content":"hello"}]}'
# X-Aether-Cache: MISS ... "content":"Echo: hello"
# Send it again: X-Aether-Cache: EXACT_HIT
```

Outside Docker, the same jar runs lite mode with
`SPRING_PROFILES_ACTIVE=lite ROUTING_CONFIG_PATH=... java -jar gateway-proxy.jar`.

## Keys at startup

Because nothing survives a restart, lite mode accepts two keys from the
environment:

| Variable | Effect |
|---|---|
| `AETHER_LITE_BOOTSTRAP_API_KEY` | Registered as a working gateway API key at every start, with no limits. Use it to call `/v1/*` without creating a key first. |
| `AETHER_ADMIN_API_KEY` | The admin key, as in full mode. **If it is not set, lite mode generates one and logs it once at startup** (a WARN line containing `generated an admin key`). Set it to keep the same key across restarts. |

Anonymous access stays off by default, exactly as in full mode.

At startup lite mode logs one WARN listing everything below that it does
not support. When it detects Kubernetes (`KUBERNETES_SERVICE_HOST` is
set) the warning is louder and says to keep `replicas: 1` with the
`Recreate` strategy.

`GET /actuator/info` reports the mode: `{"aether":{"mode":"lite"}}`
(`"full"` in full mode).

## Support matrix

| Feature | Full mode | Lite mode |
|---|---|---|
| OpenAI-compatible `/v1/chat/completions` (incl. streaming, tool calling) and `/v1/embeddings` | Yes | Yes |
| Routing, retries, failover, circuit breakers, hot reload | Yes | Yes |
| Circuit breaker hints shared between replicas (ADR-004) | Yes (Redis) | No - one replica, nothing to share |
| Per-key RPS, concurrency and monthly token budgets | Yes, shared by all replicas (Redis) | Yes, per process |
| Per-IP limit for anonymous requests | Yes (Redis) | Yes, per process; the least recently seen of 100,000 tracked IPs is dropped first |
| Quota store outage handling | Fails closed (ADR-005) | Not applicable - there is no store to lose |
| Exact-match cache | Yes (Redis + Postgres) | Yes, in memory: 10,000 entries / 64MB by default, LRU, TTL |
| Semantic cache | Yes (pgvector + ONNX) | **No.** Never returns `SEMANTIC_HIT`; a route's `cache.semantic` is ignored |
| API keys and admin API | Yes, durable | Yes, **lost on restart** (see bootstrap key above) |
| Prompt registry | Yes, durable | Yes, **lost on restart** |
| Request log, request explorer, `/admin/usage` | Full history (partitioned Postgres table) | **Last 10,000 requests only** (ring buffer), lost on restart |
| Monthly budgets | Durable for the month | **Reset on restart**, so a restart can allow overspend |
| Prometheus metrics, tracing, `/admin/*` console | Yes | Yes |
| More than one replica, overlapping rolling deploys | Yes | **No.** Limits apply per process, so N replicas enforce N times every limit |
| Moving data to the other mode | - | **No** migration path from lite to full |

The in-memory sizes are tunable: `AETHER_LITE_REQUEST_LOG_CAPACITY`,
`AETHER_LITE_CACHE_MAX_ENTRIES`, `AETHER_LITE_CACHE_MAX_BYTES` and
`AETHER_LITE_MAX_TRACKED_IPS`.

## How it is built

- Each in-memory adapter lives next to its full-mode sibling in the same
  driven-adapter module, in an `inmemory` package:
  `InMemoryApiKeyStore`, `InMemoryQuotaAdapter` and
  `InMemoryIpRateLimitAdapter` (gateway-quota), `InMemoryExactMatchCache`
  (gateway-cache), `InMemoryPromptRegistry` (gateway-registry) and
  `InMemoryRequestLog` (gateway-observability). ArchUnit keeps those
  packages free of Spring, JDBC and Redis.
- Each port has one contract test suite (`ApiKeyStoreContract`,
  `QuotaContract`, `IpRateLimitContract`, `ExactMatchCacheContract`,
  `PromptRegistryContract`, `RequestLogContract`). It runs against the
  Postgres/Redis adapter in `integrationTest` and against the in-memory
  adapter in `test`, so both modes keep the same behaviour.
- `aether.mode` (set to `lite` by the `lite` profile) selects
  `LiteModeInfrastructureConfig` instead of `FullModeInfrastructureConfig`;
  full mode is the default. `application-lite.yml` excludes the
  DataSource, JDBC, Flyway and Redis auto-configuration, and a Spring
  context test checks that lite mode creates no DataSource, Redis or
  embedding-model bean.
- The acceptance suite runs in both modes:
  `./gradlew :gateway-acceptance-tests:test` (full) and
  `./gradlew :gateway-acceptance-tests:acceptanceTestLite` (lite, skipping
  scenarios tagged `@requires-semantic-cache`). Scenarios only meaningful
  in lite mode are tagged `@lite-only`.

## Limitations and open questions

- **Local providers on the same host.** `ProviderBaseUrlValidator`
  rejects provider base URLs that resolve to loopback or private
  addresses (SSRF protection, F9.3), in lite mode as in full mode. So an
  Ollama on `localhost` (or on a private network) cannot be used as a
  provider. Whether to add an explicit opt-in such as
  `aether.security.allow-private-provider-addresses` is a security
  decision for the maintainer and is not part of lite mode.
- **Provider DNS at startup.** The bundled routing policy names real
  provider hosts, and their addresses are resolved and validated when
  the policy loads. A container that cannot resolve DNS fails to start;
  mount a routing policy with only the `echo` provider to run fully
  offline.
- **Budgets and restarts.** Monthly budget counters reset on restart.
  Should lite mode refuse to start when budgeted keys exist and no
  snapshot is configured? There is no snapshot yet, and keys do not
  survive a restart either, so the question becomes real only once
  persistence is added.
- **Image size.** The lite image is the full image plus two config
  files: same glibc base, and the ONNX runtime jars are still on the
  classpath, although never loaded. A slimmer lite image would need a
  separate jar without the embedding dependency.
- **Bare jar.** Running the jar directly still needs
  `ROUTING_CONFIG_PATH` and `COST_MODEL_CONFIG_PATH`; only the image
  bundles defaults.
- Kubernetes manifests (`k8s/`) stay full-mode only.
