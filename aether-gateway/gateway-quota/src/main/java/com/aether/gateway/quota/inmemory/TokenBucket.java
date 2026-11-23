package com.aether.gateway.quota.inmemory;

import java.time.Duration;
import java.time.Instant;

/** F5.1 / F9.5: the same continuous-refill bucket as lua/token_bucket.lua, as an immutable value. */
record TokenBucket(double tokens, Instant lastRefill) {

    static TokenBucket full(int ratePerSecond, Instant now) {
        return new TokenBucket(ratePerSecond, now);
    }

    TokenBucket refilled(int ratePerSecond, Instant now) {
        double elapsedSeconds = Math.max(0, Duration.between(lastRefill, now).toNanos()) / 1_000_000_000.0;
        return new TokenBucket(Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond), now);
    }

    boolean hasToken() {
        return tokens >= 1;
    }

    TokenBucket withOneTokenTaken() {
        return new TokenBucket(tokens - 1, lastRefill);
    }
}
