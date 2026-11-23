---
title: "ADR-010: Lite mode - single-process in-memory adapters"
slug: docs/adr/lite-mode
sidebar:
  order: 10
---

## Context

Trying Aether today means `docker compose up`: Postgres with pgvector,
Redis, two mock providers and a first-boot download of a ~90MB ONNX
embedding model. That is the single biggest adoption barrier compared
with tools that start from one `pip install`. Many evaluations, and many
small deployments, need exactly one gateway process and none of the
multi-replica guarantees Postgres and Redis exist to provide.

Every infrastructure dependency already sits behind a gateway-core port
(ADR-001): `ApiKeyLookupPort`, `ApiKeyAdminPort`, `QuotaPort`,
`IpRateLimitPort`, `CachePort`, `PromptRegistryPort`, `RequestLogPort`,
`RequestLogQueryPort`, `UsageQueryPort`. The breaker hint
(`BreakerHintGateway`) is already optional, and ADR-004 allows it to be
absent for a single replica. So the question is only which second set of
adapters to provide, and how to select it.

Embedded SQL databases (SQLite, H2) were considered and rejected. The
JDBC adapters use Postgres-only SQL (`::jsonb`, `ON CONFLICT`, `TEXT[]`,
a range-partitioned `request_log`), so an embedded database would mean
forking every statement and every migration and keeping two dialects in
sync forever. It would also still not give us semantic search without
pgvector.

## Decision

Add **lite mode**, selected with `SPRING_PROFILES_ACTIVE=lite`, which
sets `aether.mode=lite`:

1. **In-memory adapters, not an embedded database.** Each one is pure
   JDK code living next to its full-mode sibling in the same driven
   adapter module (`InMemoryApiKeyStore` in gateway-quota,
   `InMemoryExactMatchCache` in gateway-cache, and so on), in an
   `inmemory` sub-package that ArchUnit keeps free of Spring, JDBC and
   Redis.
2. **One contract test suite per port**, characterised first against the
   existing Redis/JDBC adapters (in `integrationTest`), then run
   unchanged against the in-memory adapter (in `test`). The contract is
   what both modes promise; anything outside it is mode-specific and
   tested where it lives (ADR-009 rule 3).
3. **One switch, not one per port.** Beans are chosen with
   `@ConditionalOnProperty(name = "aether.mode")`; full mode stays the
   default (`matchIfMissing = true`). Per-port store switches are
   rejected: `request_log.api_key_id` has a foreign key to `api_key`, so
   mixing stores breaks, and the test matrix would multiply.
4. **Semantic cache is off.** Lite mode never constructs the embedding
   model, so the cache is exact-match only and never answers
   `SEMANTIC_HIT`, whatever a route's `cache.semantic` says.
5. **One jar, two image targets.** The Dockerfile gains a `lite` stage
   that sets the profile and bundles a default routing file; `full`
   remains the last stage, so a plain `docker build` is unchanged.
6. **No breaker hint.** `BreakerHintGateway` is injected through
   `ObjectProvider.getIfAvailable()` and is null in lite mode, which
   `ResilientRouter` already handles.

## Consequences

- `docker run -p 8080:8080 <image>:lite` is a complete gateway: routing,
  failover, quotas, exact-match cache, prompt registry, request log,
  admin API and metrics, with no other container.
- Lite mode is **single-replica and non-durable**, and the startup log
  says so in one WARN line:
  - Limits are enforced per process. N replicas give N times the limits,
    so rolling deploys that overlap two pods are unsupported; use
    `Recreate`.
  - API keys, prompts, the request log and monthly budget counters reset
    on restart, so a restart can allow budget overspend.
  - No semantic cache; usage history is limited to the request log's
    in-memory ring buffer; no breaker hints shared between replicas; no
    migration path from lite data to full mode.
- Because API keys do not survive a restart, lite mode accepts an
  optional bootstrap key from the environment, and generates and logs an
  admin key once when none is configured.
- The in-memory quota adapter enforces RPS, concurrency and budget under
  one lock per key, so it cannot hit the partial-acquisition cases the
  Redis adapter has to roll back; it has no store to lose, so it has no
  fail-closed path either.
- The full-mode adapters and their behaviour are untouched. The lite
  image is still large (glibc base, ONNX jars on the classpath though
  never loaded); a slim jar is a possible follow-up.
- `ProviderBaseUrlValidator` still rejects loopback and private provider
  addresses in lite mode, so an Ollama on the same host is blocked.
  Loosening that is a security decision left open, not part of this ADR.
