package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.port.QuotaPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** F5: the quota contract against a real Redis (Testcontainers), never a mock. */
class RedisQuotaContractIntegrationTest extends QuotaContract {

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisQuotaAdapter quotaAdapter;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        var template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        quotaAdapter = new RedisQuotaAdapter(
                new RedisTokenBucket(template), new RedisConcurrencyCap(template), new RedisMonthlyBudget(template));
        // Lettuce's first real call pays the connection set-up cost, which
        // can exceed the one second a 1 RPS bucket needs to refill.
        quotaAdapter.checkAndReserve(new ApiKeyContext("warm-up", 1, 1, 1L), "warm-up", 1);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @Override
    protected QuotaPort quota() {
        return quotaAdapter;
    }
}
