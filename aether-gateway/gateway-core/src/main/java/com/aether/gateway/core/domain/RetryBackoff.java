package com.aether.gateway.core.domain;

import java.util.Random;

/**
 * F3.3: exponential backoff with FULL jitter (not a fixed or
 * decorrelated jitter), per the "full jitter" formula: the delay is
 * uniformly random between 0 and {@code min(cap, base * 2^attempt)}.
 * Full jitter specifically avoids synchronized retry storms across
 * replicas, which a naive fixed-delay backoff does not.
 */
public class RetryBackoff {

    private final long baseMillis;
    private final long capMillis;

    public RetryBackoff(long baseMillis, long capMillis) {
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
    }

    public long delayMillis(int attempt, Random random) {
        long exponential = (long) (baseMillis * Math.pow(2, attempt));
        long cap = Math.min(capMillis, exponential);
        if (cap <= 0) {
            return 0;
        }
        return (long) (random.nextDouble() * cap);
    }
}
