---
title: "Module boundaries"
---

Gradle multi-module layout and dependency direction, per ADR-001. This is
what Phase 03's ArchUnit rules encode mechanically.

```
aether-gateway/
├── gateway-core/           domain model, ports/interfaces. Depends on: nothing but the JDK.
├── gateway-router/         orchestration: implements driving ports via driven ports. Depends on: gateway-core.
├── gateway-proxy/          HTTP/SSE driving adapter + the deployable Spring Boot app. Depends on: gateway-core, gateway-router, gateway-providers, gateway-quota, gateway-cache, gateway-registry, gateway-observability, gateway-admin.
├── gateway-providers/      driven adapter: ProviderAdapter implementations (mock, later ollama/groq/gemini). Depends on: gateway-core.
├── gateway-cache/          driven adapter: CachePort against pgvector + Redis. Depends on: gateway-core.
├── gateway-quota/          driven adapter: QuotaPort against Redis + Lua. Depends on: gateway-core.
├── gateway-registry/       driven adapter: PromptRegistryPort against Postgres. Depends on: gateway-core.
├── gateway-observability/  driven adapter: RequestLogPort, metrics port. Depends on: gateway-core.
├── gateway-admin/          HTTP driving adapter, control plane. Depends on: gateway-core, gateway-router.
├── gateway-bench/          driving adapter: load generator, eval harness (Phase 09). Depends on: gateway-core (request/response shapes only, for building realistic load).
├── mock-provider/          standalone Spring Boot app, NOT part of the hexagon. Depends on: nothing internal.
└── gateway-acceptance-tests/  Cucumber-JVM suite, driving adapter. Depends on: an HTTP test client only. MUST NOT depend on gateway-core or any other internal module.
```

## Dependency direction rule

`gateway-core` depends on nothing but the JDK (plus JUnit 5/AssertJ as
test-only dependencies). Every other module depends inward, toward
`gateway-core`, directly or transitively. No module `gateway-core`
depends on may exist; ArchUnit fails the build if this is violated.

`gateway-proxy` is the one module allowed to depend on every other
gateway-* module, because it hosts the deployable Spring Boot application
(`@SpringBootApplication` main class) that assembles the whole system at
runtime via component scanning and dependency injection. This is a
one-directional aggregation dependency for wiring purposes only;
`gateway-router`, `gateway-providers`, etc. never depend back on
`gateway-proxy`.

`gateway-admin` depends on `gateway-core` and `gateway-router` (to call
the same driving ports `gateway-proxy` calls, for endpoints like
`POST /admin/routes/reload` and `GET /admin/providers/{name}/breaker`),
but not on `gateway-proxy`.

`mock-provider` is architecturally external to the gateway; it is built
as a separate Spring Boot application in the same Gradle build for
convenience (one `docker compose up` brings both up), but shares no
gateway-* module dependency. `gateway-providers`' `MockProviderAdapter`
talks to it exclusively over HTTP, the same way it will later talk to a
real provider.

`gateway-acceptance-tests` depends only on an HTTP test client and
Cucumber/JUnit test infrastructure. It drives the running system
(started via Testcontainers or against the Compose stack) purely through
its public HTTP/SSE surface and must never import a class from
`gateway-core` or any other internal module; doing so would let a
behavioral test assert on internals instead of client-observable
behavior, defeating its purpose as a driving adapter.

## Verification

Every item in this list is checked against PRD section 6's module list
line by line before Phase 02 is marked done: `gateway-core`,
`gateway-proxy`, `gateway-router`, `gateway-providers`, `gateway-cache`,
`gateway-quota`, `gateway-registry`, `gateway-observability`,
`gateway-admin`, `gateway-bench`, `mock-provider` all present, matching
PRD section 6 exactly. `gateway-acceptance-tests` is an addition beyond
the PRD's module list, justified by ADR-009 as the home for the
Cucumber-JVM behavioral test suite; it introduces no production
dependency and does not change the hexagon itself.
