package com.aether.gateway.quota;

import com.aether.gateway.core.port.IpRateLimitPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** F9.5: the per-IP limiter contract against a real Redis (Testcontainers). */
class RedisIpRateLimitContractIntegrationTest extends IpRateLimitContract {

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

    @Override
    protected IpRateLimitPort limiterAllowingOneRequestPerSecond() {
        return new RedisIpRateLimitAdapter(template, 1);
    }
}
