package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.quota.IpRateLimitContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIpRateLimitAdapterTest extends IpRateLimitContract {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));

    @Override
    protected IpRateLimitPort limiterAllowingOneRequestPerSecond() {
        return new InMemoryIpRateLimitAdapter(1, 100, clock);
    }

    @Test
    void anIpIsAdmittedAgainOnceItsBucketRefills() {
        var limiter = new InMemoryIpRateLimitAdapter(1, 100, clock);
        limiter.tryConsume("203.0.113.1");

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.tryConsume("203.0.113.1")).isTrue();
    }

    @Test
    void onlyTheMostRecentlySeenIpsAreTracked() {
        var limiter = new InMemoryIpRateLimitAdapter(1, 2, clock);
        limiter.tryConsume("203.0.113.1");
        limiter.tryConsume("203.0.113.2");
        limiter.tryConsume("203.0.113.3");

        assertThat(limiter.trackedIpCount()).isEqualTo(2);
        assertThat(limiter.tryConsume("203.0.113.3")).isFalse();
        assertThat(limiter.tryConsume("203.0.113.1")).as("evicted, so it starts with a full bucket").isTrue();
    }
}
