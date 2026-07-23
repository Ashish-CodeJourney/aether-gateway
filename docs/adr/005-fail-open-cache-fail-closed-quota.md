# ADR-005: Fail-open cache, fail-closed quota

## Context

Both the semantic cache (Phase 07) and the quota system (Phase 06) depend
on infrastructure that can become unavailable (Postgres/pgvector for the
cache, Redis for both, notably for quota). When that infrastructure is
down, the gateway must decide, per subsystem, whether to let requests
through anyway or reject them. Treating both subsystems the same way "for
consistency" is tempting but wrong: the two failures have different
costs.

## Decision

If the cache backend is unavailable, bypass the cache and serve from the
provider: correctness is preserved, only cost efficiency degrades. If the
quota store (Redis) is unavailable, reject the request (fail closed):
allowing requests through with no quota check risks unbounded, unmetered
spend, which is the worse failure. These are opposite failure policies
for the two subsystems, chosen deliberately, not by oversight.

## Consequences

- `QuotaPort` implementations must reject (return a fail-closed result,
  which the router maps to a 5xx or 429-shaped rejection) when Redis is
  unreachable, never silently allow the request through. This is tested
  directly with a fake `QuotaPort` that simulates unavailability, and
  later with a chaos test that kills the real Redis container mid-run
  (PRD section 15, "Chaos" row).
- `CachePort` implementations must catch backend unavailability and
  return a miss/bypass outcome rather than propagating an error that
  would fail the whole request, once cache logic exists in Phase 07.
- This asymmetry is a named interview talking point (PRD section 21, item
  7) and must not be "simplified" away later for uniformity.
