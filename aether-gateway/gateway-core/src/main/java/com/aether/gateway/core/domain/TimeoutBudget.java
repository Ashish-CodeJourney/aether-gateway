package com.aether.gateway.core.domain;

import java.time.Duration;

/**
 * F3.7: a total per-request time budget, decremented as it is spent
 * across failover attempts, so a chain of N providers cannot each
 * individually consume a full timeout (which would let total request
 * latency balloon to N times the per-attempt timeout).
 */
public class TimeoutBudget {

    private Duration remaining;

    public TimeoutBudget(Duration total) {
        this.remaining = total;
    }

    public void spend(Duration amount) {
        remaining = remaining.minus(amount);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
    }

    public Duration remaining() {
        return remaining;
    }

    public boolean isExhausted() {
        return remaining.isZero();
    }
}
