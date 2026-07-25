package com.aether.gateway.quota;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** F9.5: proves the per-IP limit actually rejects once exceeded, and that two different IPs don't share a bucket. */
class RedisIpRateLimitAdapterIntegrationTest {

    private static GenericContainer<?> redis;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();
        var connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        // Lettuce's *first* real call pays connection-establishment cost
        // (measured over 1000ms in this sandbox) - for a 1-request-per-
        // second bucket that easily masquerades as "a whole second
        // legitimately elapsed" between two back-to-back calls in a
        // test. Warming the connection up here, once, keeps every
        // actual test's timing assumptions about *its own* calls valid.
        new RedisIpRateLimitAdapter(template, 1).tryConsume("0.0.0.0-warmup");
    }

    @AfterAll
    static void stopRedis() {
        redis.stop();
    }

    @Test
    void allowsRequestsWithinTheLimitAndRejectsOnceExceeded() {
        // capacity/refill = 1 req/sec: refilling a single token takes
        // ~1000ms, comfortably longer than these two calls' real Redis
        // round-trip time, so this stays reliable even under a loaded
        // test host - a higher rpsLimit here would make the test
        // flaky (the token bucket refills continuously, not just once
        // per second, so a handful of slow round-trips can legitimately
        // refill a fractional token between calls).
        var adapter = new RedisIpRateLimitAdapter(template, 1);
        String ip = "203.0.113." + (1 + (int) (Math.random() * 250));

        assertThat(adapter.tryConsume(ip)).isTrue();
        assertThat(adapter.tryConsume(ip)).isFalse();
    }

    @Test
    void differentIpAddressesHaveIndependentBuckets() {
        var adapter = new RedisIpRateLimitAdapter(template, 1);
        String ipA = "198.51.100.10";
        String ipB = "198.51.100.20";

        assertThat(adapter.tryConsume(ipA)).isTrue();
        assertThat(adapter.tryConsume(ipA)).isFalse();
        assertThat(adapter.tryConsume(ipB)).isTrue();
    }
}
