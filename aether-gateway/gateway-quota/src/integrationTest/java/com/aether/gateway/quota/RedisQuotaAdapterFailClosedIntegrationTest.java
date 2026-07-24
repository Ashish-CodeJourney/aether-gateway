package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-005 / Phase 06 task 8 and risk "Fail-closed vs. fail-open
 * confusion": when the quota store is unreachable, requests must be
 * rejected, never silently allowed through unmetered. Proven here by
 * actually stopping the Redis container mid-test (a real chaos test, not
 * a mocked exception), the same way PRD section 15's "Chaos" row
 * describes for the quota half of that row.
 */
class RedisQuotaAdapterFailClosedIntegrationTest {

    @Test
    void requestsAreRejectedNotSilentlyAllowedWhenRedisIsUnreachable() {
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        var template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        var quotaAdapter = new RedisQuotaAdapter(
                new RedisTokenBucket(template),
                new RedisConcurrencyCap(template),
                new RedisMonthlyBudget(template));

        var key = new ApiKeyContext("m3-fail-closed-test-" + UUID.randomUUID(), 1000, 1000, 1000L);

        // Sanity check: quota enforcement works normally before the outage.
        QuotaDecision beforeOutage = quotaAdapter.checkAndReserve(key, "req-before-outage", 1);
        assertThat(beforeOutage).isInstanceOf(QuotaDecision.Allowed.class);

        redis.stop();

        try {
            QuotaDecision duringOutage = quotaAdapter.checkAndReserve(key, "req-during-outage", 1);

            assertThat(duringOutage)
                    .as("Redis unavailable must fail closed (reject), never fail open (silently allow)")
                    .isInstanceOf(QuotaDecision.Rejected.class);
            assertThat(((QuotaDecision.Rejected) duringOutage).reason()).isEqualTo("quota_store_unavailable");
        } finally {
            connectionFactory.destroy();
        }
    }
}
