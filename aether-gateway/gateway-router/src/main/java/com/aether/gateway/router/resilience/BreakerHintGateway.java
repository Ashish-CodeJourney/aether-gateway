package com.aether.gateway.router.resilience;

/**
 * ADR-004: the Redis-published advisory breaker hint. {@code publishOpen}
 * is called when a local breaker transitions to OPEN; {@code isHintedOpen}
 * is consulted before treating a CLOSED local breaker as usable, to
 * fast-open based on another replica's observation. Never used to
 * fast-close: a hint absence does not by itself close a breaker, only
 * local recovery evidence does that (Resilience4j's own state machine).
 * May be {@code null} in single-replica/local-dev contexts; callers must
 * null-check.
 */
public interface BreakerHintGateway {
    void publishOpen(String provider, String model);

    boolean isHintedOpen(String provider, String model);
}
