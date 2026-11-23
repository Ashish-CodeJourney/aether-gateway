package com.aether.gateway.quota;

import com.aether.gateway.core.port.IpRateLimitPort;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F9.5: what every per-IP limiter promises, whichever mode the gateway
 * runs in (ADR-010). One request per second, so refilling a single
 * token takes far longer than two back-to-back calls.
 */
public abstract class IpRateLimitContract {

    protected abstract IpRateLimitPort limiterAllowingOneRequestPerSecond();

    private static String freshIp() {
        return "ip-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsWithinTheLimitAndRejectsOnceExceeded() {
        IpRateLimitPort limiter = limiterAllowingOneRequestPerSecond();
        String ip = freshIp();

        assertThat(limiter.tryConsume(ip)).isTrue();
        assertThat(limiter.tryConsume(ip)).isFalse();
    }

    @Test
    void differentIpAddressesHaveIndependentBuckets() {
        IpRateLimitPort limiter = limiterAllowingOneRequestPerSecond();
        String ipA = freshIp();
        String ipB = freshIp();

        assertThat(limiter.tryConsume(ipA)).isTrue();
        assertThat(limiter.tryConsume(ipA)).isFalse();
        assertThat(limiter.tryConsume(ipB)).isTrue();
    }
}
