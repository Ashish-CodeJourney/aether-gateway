# Aether Gateway

**A self-hosted LLM gateway — streaming proxy, multi-provider failover, and vector-based semantic caching in Java 25 / Spring Boot 4.1 — measured to cut cached-request latency to 8.9ms p95 and fail over to a healthy provider in 67.92ms p99, both against a reproducible load-test harness, not estimates.**

Point your existing OpenAI-compatible SDK at Aether instead of at a provider directly. It handles routing, failover, semantic caching, quota enforcement, cost accounting, and observability transparently — see [`docs/PRD.md`](docs/PRD.md) for the full requirements this was built against.

## Demo: failover and a cache hit, live

No recorded GIF ships in this repo (no live public deployment exists to record against — see [Status](#status--what-this-is) below) — instead, this is the *exact* reproducible sequence, run against `docker compose up` on this machine, with real captured output:

```console
$ curl -sS -X POST http://localhost:8080/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{"model":"mock","messages":[{"role":"user","content":"What is 2+2?"}]}' -D -
HTTP/1.1 200 OK
X-Aether-Cache: MISS
X-Aether-Cost-USD: 0.0000195
X-Aether-Attempts: 1
X-Aether-Provider: mock-primary
...

# Same prompt again — served from cache, zero cost, zero attempts:
$ curl -sS -X POST http://localhost:8080/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{"model":"mock","messages":[{"role":"user","content":"What is 2+2?"}]}' -D -
HTTP/1.1 200 OK
X-Aether-Cache: EXACT_HIT
X-Aether-Cost-USD: 0
...

# Force the primary provider to fail, then send a new prompt — the gateway
# fails over to mock-fallback transparently, same request, no client change:
$ curl -sS -X POST http://localhost:8082/_mock/config \
    -H "Content-Type: application/json" -d '{"fail_mode":"503","fail_rate":1.0}'
$ curl -sS -X POST http://localhost:8080/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{"model":"mock","messages":[{"role":"user","content":"failover demo"}]}' -D -
HTTP/1.1 200 OK
X-Aether-Cache: MISS
X-Aether-Attempts: 1
X-Aether-Provider: mock-fallback
...
```

## Architecture

```mermaid
flowchart TB
    Client["OpenAI-compatible client SDK<br/>(baseUrl override)"]

    subgraph Gateway["Aether Gateway"]
        direction TB
        AuthN["1. AuthN<br/>API key to KeyCtx"]
        Quota["2. Quota<br/>Redis token bucket, Lua, atomic"]
        Prompt["3. Prompt resolution<br/>registry, versioned"]
        Cache["4. Semantic cache lookup<br/>embed to ANN to threshold"]
        Router["5. Router<br/>policy to provider chain"]
        Resilience["6. Resilience<br/>breaker / retry / bulkhead"]
        Adapter["7. Provider adapter<br/>normalise req/resp"]
        Stream["8. Stream pump<br/>SSE relay + cancellation"]
        Accounting["9. Accounting (async)<br/>tokens, cost, cache write"]

        AuthN --> Quota --> Prompt --> Cache --> Router --> Resilience --> Adapter --> Stream --> Accounting
    end

    Redis[("Redis<br/>quotas, breaker hints")]
    Postgres[("Postgres + pgvector<br/>cache, logs, registry")]
    Ollama["Ollama (local)"]
    Groq["Groq"]
    Gemini["Gemini"]
    Mock["MockProvider<br/>(fault injection)"]

    Client --> AuthN
    Quota <--> Redis
    Cache <--> Postgres
    Adapter --> Ollama
    Adapter --> Groq
    Adapter --> Gemini
    Adapter --> Mock

    Accounting --> Micrometer["Micrometer / OTel"]
```

Full diagram and narrative: [`docs/design/architecture-diagram.md`](docs/design/architecture-diagram.md). Module boundaries (hexagonal architecture — `gateway-core` depends on nothing but the JDK): [`docs/design/module-boundaries.md`](docs/design/module-boundaries.md).

## Quickstart

```bash
docker compose up -d
# wait ~30s on first run (ONNX embedding model download), then:
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"mock","messages":[{"role":"user","content":"hello"}]}'
```

That's the whole stack — gateway, Postgres+pgvector, Redis, two mock providers (standing in for real ones so failover is demonstrable without live provider outages), Prometheus, and Grafana (`http://localhost:3000`, dashboard pre-provisioned) — seeded and ready with no manual setup steps.

Point a real client at it by setting `baseUrl` to `http://localhost:8080/v1` — any OpenAI-compatible SDK works unmodified.

Admin API and console (prompt registry, API keys, cache stats, request explorer): `AETHER_ADMIN_API_KEY` is `dev-admin-key-do-not-use-in-production` in `docker-compose.yml`; see [`console/README.md`](console/README.md) to run the operator console against it.

## Benchmark results

Full tables, methodology, and raw data: **[`BENCHMARKS.md`](BENCHMARKS.md)**, reproducible via `make bench`.

| Metric | Result | Target |
|---|---|---|
| Semantic cache hit rate (502-prompt labelled corpus, entity guard active) | 13.16% | 35% (not met — see below) |
| Semantic cache false-hit rate | 8.0% | 5% (not met — see below) |
| Cache-hit latency, p95 | 8.9ms | 50ms |
| Failover latency, p99 (100 trials) | 67.92ms | 500ms |
| Line coverage (4 core modules) | 76.8% | — |

The hit-rate/false-hit-rate gap is disclosed, not hidden: `docs/design/cache-correctness.md` explains why (negation and spelled-out-number gaps in the entity guard's defined scope), and the AC scorecard in `BENCHMARKS.md` documents every acceptance criterion measured, met or not.

## Design decisions and tradeoffs

- **WebFlux for the streaming proxy path, virtual threads everywhere else** — cancellation propagation (closing a browser tab must actually stop paying for tokens) needs Reactor's operator chain; nothing else on the request path benefits from non-blocking I/O enough to justify its complexity. [ADR-002](docs/adr/002-webflux-streaming-mvc-virtual-threads-elsewhere.md).
- **Circuit breaker state: local per replica, with a Redis advisory hint, not shared** — a genuinely shared breaker needs a distributed consensus problem this project doesn't need to solve; a hint that nudges replicas toward the same view without requiring one is a real, deliberate compromise, not a shortcut. [ADR-004](docs/adr/004-circuit-breaker-state-local-with-redis-advisory-hint.md).
- **Fail-open cache, fail-closed quota** — a cache outage should degrade to "always call the real provider," never block traffic; a quota outage must never silently let unmetered spend through. Same infrastructure (Redis), opposite failure posture, on purpose. [ADR-005](docs/adr/005-fail-open-cache-fail-closed-quota.md).
- **Semantic cache threshold (0.94) and the entity/numeric guard** — similarity alone can't distinguish "what is 2+2" from "what is 2+3"; the guard is a deliberate, no-network heuristic layered on top of the threshold, not a replacement for it. [`docs/design/cache-correctness.md`](docs/design/cache-correctness.md).
- **Reservation-and-reconciliation for token quotas** — output token count isn't known until a response completes, so quota is reserved pessimistically up front and reconciled to the real count afterward, rather than either blocking on the real number or risking overspend. [ADR-006](docs/adr/006-reservation-and-reconciliation-for-token-quotas.md).
- **Provider credentials via environment variable *names*, never values, in routing.yaml** (F9.2) — `apiKeyEnvVar: GROQ_API_KEY` in config, resolved from the real environment only at the one place adapters are wired up, so routing.yaml is safe to commit even with real providers configured.
- **SSRF protection on real, operator-configured provider base URLs** (F9.3) — a gateway that dials operator-configured URLs is exactly the threat model this exists for. The PRD names Spring Boot 4.1's `InetAddressFilter` for this; that class does not exist in Spring Framework 7.0.8 / Boot 4.1 (checked directly against the dependency jars before building anything), so this is built on `java.net.InetAddress`'s own private-range predicates instead — `PrivateNetworkAddresses` (pure) + `ProviderBaseUrlValidator` (the DNS-resolution half), rejecting a routing-policy reload outright if any real provider's base URL resolves to a private/reserved address. Proven at the unit, integration, *and* acceptance level (`@m9 @F9.3`).
- **Graceful SSE drain under a Kubernetes rolling update** — readiness flips before liveness, a `preStop` sleep gives the Service time to stop routing new traffic before SIGTERM, and in-flight streams finish naturally rather than being cut. Proven live: 500/500 concurrent streams survived a real rolling update, run twice, zero broken both times. [`docs/design/kubernetes-deployment.md`](docs/design/kubernetes-deployment.md).
- **Autoscaling on in-flight stream count, not CPU** — these pods are I/O-bound waiting on upstream providers; CPU sits near idle while genuinely saturated. Wired to `aether_gateway_in_flight_streams` via the HPA's custom-metric config (disclosed: the Prometheus Adapter needed to actually serve that metric wasn't deployed in this session's cluster, so the scale-out event itself wasn't exercised live).

## What was deliberately not built, and why

Per the PRD's own non-goals (section 3.2) — being able to name what you chose *not* to build is itself the point, not an oversight:

- Training, fine-tuning, or hosting models — this is infrastructure *around* model calls, not a model-serving project.
- A chat UI for end users — the operator console (`console/`) is an internal tool for API keys, prompts, cache stats, and the request log, not a product surface.
- Multi-tenant billing, invoicing, or payments.
- Agent frameworks, tool-calling orchestration, or RAG pipelines.
- Fine-grained RBAC beyond API-key scopes.
- Horizontal DB sharding or global multi-region.
- Helm, a service mesh, Kubernetes operators/CRDs, or ArgoCD — named directly in the PRD as "unjustified complexity you would have to defend" for a project at this scale; plain manifests plus `kubectl` cover everything actually needed.

## Local development

```bash
cd aether-gateway
./gradlew test integrationTest          # unit + Testcontainers-backed integration tests
./gradlew :gateway-acceptance-tests:test  # full Cucumber acceptance suite (@m0-@m9)
```

Module layout, hexagonal boundaries, and where a new provider adapter or route type belongs: [`docs/design/module-boundaries.md`](docs/design/module-boundaries.md) and [ADR-001](docs/adr/001-hexagonal-module-layout.md). Full requirements and phase-by-phase build plan: [`docs/PRD.md`](docs/PRD.md) and [`docs/plan/`](docs/plan/) (see [`docs/plan/STATUS.md`](docs/plan/STATUS.md) for exactly what's done, what's disclosed-incomplete, and why, phase by phase).

## Status — what this is

Built through Phase 12 (M9) of the plan: real provider adapters (Ollama, Groq, Gemini, and a generic OpenAI-compatible adapter — all conforming to the identical `ProviderAdapter` port the mock provider does, with zero changes to `gateway-core` or `gateway-router`), the security hardening in PRD section 7 (F9.1–F9.5), and a Kubernetes deployment with a live-proven zero-downtime rolling update.

**Not deployed to a live public URL.** That specific piece of M9's exit criterion needs real provider API keys and cloud infrastructure access this build environment doesn't have; per [`docs/plan/STATUS.md`](docs/plan/STATUS.md)'s Phase 12 entry, this was a deliberate scope decision, not an oversight — `docker compose up` brings up the complete, real, fully-functional stack locally, which is what every command and result on this page was actually run against.
