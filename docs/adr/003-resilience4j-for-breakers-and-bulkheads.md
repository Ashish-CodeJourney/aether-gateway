# ADR-003: Resilience4j for breakers and bulkheads, Spring annotations where they suffice

## Context

Spring Framework 7 ships `@Retryable` and `@ConcurrencyLimit` in core,
which cover simple retry and concurrency-limiting needs without an extra
dependency. But this gateway needs circuit breakers with rich state
machine semantics (closed/open/half-open transitions, configurable
failure-rate and slow-call thresholds), state transition events (to
publish the Redis advisory hint in ADR-004), and a Micrometer metrics
binder for breaker state dashboards (F3.8, F6.1). Spring's core
annotations do not provide a breaker abstraction at all, only retry and
concurrency limiting.

## Decision

Use Resilience4j for circuit breakers (F3.1) and bulkheads (F3.4), keyed
per (provider, model) pair. Use Spring Framework 7's core `@Retryable`
only where Resilience4j's richer retry semantics are not needed; in
practice, this project uses Resilience4j's retry module too, since full
jitter (F3.3) and honoring `Retry-After` (F3.6) are more naturally
expressed there alongside the breaker they interact with. The concrete
split: Resilience4j owns breaker, retry, and bulkhead for the
provider-call path in `gateway-router`; Spring's `@ConcurrencyLimit` is
available for any simple, non-provider-facing concurrency limiting
elsewhere if a future phase needs it, but nothing in M0-M3 does.

## Consequences

- One more dependency (`resilience4j-spring-boot3`) beyond what Spring
  Boot's BOM already provides, justified by breaker semantics and metrics
  binding that core Spring does not offer.
- Breaker, retry, and bulkhead configuration live together in
  `gateway-router`'s Resilience4j configuration, keyed by
  `(provider, model)`, not by provider alone (F3.1's explicit
  requirement).
- Resilience4j's `CircuitBreakerRegistry` event stream is the mechanism
  ADR-004's Redis advisory hint publishes from: a breaker transitioning
  to OPEN locally triggers a hint write, never the reverse.
