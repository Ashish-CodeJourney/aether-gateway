# ADR-004: Circuit breaker state is local per JVM, with a Redis advisory hint

## Context

Under N replicas, a fully local circuit breaker means each pod
independently discovers a dead provider, wasting up to N times the probe
requests before every replica has opened its own breaker. Sharing breaker
state fully in Redis would make state consistent across replicas
immediately, but places Redis on the hot request-serving path for every
provider call, turning a Redis outage into a gateway-wide provider outage
even for providers that are actually healthy (F3.9, called out explicitly
in the PRD as a decision worth documenting).

## Decision

Keep circuit breaker state local per JVM (Resilience4j's own state
machine, per ADR-003), and add a Redis-published advisory health hint,
`cb:hint:{provider}:{model}` (PRD section 12.2, 30s TTL), as an
additional *input* to opening a breaker faster, never as authority to
close one. Concretely: when a local breaker transitions to OPEN, the
transition is published as a hint other replicas can read. A replica
observing that hint may use it to open its own breaker for that
(provider, model) pair sooner than its own local failure count would
otherwise trigger (fast-open). A replica's breaker may only transition
back to CLOSED or HALF_OPEN based on its own local, direct evidence of
recovery (successful probe calls); the Redis hint is never consulted to
justify closing a breaker (no fast-close).

This asymmetry is deliberate and easy to get backwards: fast-open is safe
because a false-positive hint just means one replica probes a live
provider slightly less; fast-close based on another replica's stale hint
could route real traffic to a provider that is still failing for the
observing replica's own network path.

## Consequences

- No coordination round-trip on the hot path: reading local breaker state
  costs nothing extra per request. Redis is only touched on state
  transition (rare) and on the periodic hint check (cheap, off the
  critical decrement path).
- If Redis is unavailable, breakers still function correctly using only
  local state; the hint mechanism degrading to a no-op is an acceptable,
  intentional loss of an optimization, not a correctness failure. This is
  consistent with the cache's fail-open policy (ADR-005) in spirit, even
  though breaker state is not the cache.
- Testing this requires both a single-replica unit/integration test of
  local breaker behavior, and (later, if multi-replica testing is added)
  a test that a published hint accelerates a second replica's breaker
  opening without ever closing it early.

## Addendum (Phase 05 implementation note)

A real bug surfaced during manual M2 verification, worth recording
because it is a subtler version of "never fast-close" than the original
decision anticipated. The hint's 30s TTL legitimately outlives a much
shorter local `waitDurationInOpenState` (5s in this project's config).
The first implementation consulted `isHintedOpen` on every single call
whenever the local breaker was CLOSED; since the *same* replica that
opened the breaker also published the hint, and kept it fresh with every
subsequent blocked call, the hint stayed alive well past the point the
breaker had already recovered locally via its own half-open probes, so
the very next call would immediately force it back OPEN, permanently
starving recovery even though nothing was actually still failing.

The fix: only consult the hint when the breaker has **no local call
history at all** (`getMetrics().getNumberOfSuccessfulCalls() +
getNumberOfFailedCalls() == 0`), not merely "currently CLOSED." This
matches the hint's actual purpose, letting a freshly-started replica with
zero data of its own fast-open based on another replica's observation,
while guaranteeing that the moment a breaker has any local evidence,
fresh or stale, local evidence always wins over a hint, closing the loop
this ADR's "never fast-close" language did not fully anticipate: a hint
must not just fail to *close* a breaker, it must not re-*open* one that
local evidence has already closed.
