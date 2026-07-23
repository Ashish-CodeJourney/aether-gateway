package com.aether.gateway.router.resilience;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves RedisBreakerHintGateway is a faithful implementation against a
 * real Redis, per TESTING-STRATEGY.md's integration-test layer for
 * driven adapters.
 */
class RedisBreakerHintGatewayIntegrationTest {

    private static GenericContainer<?> redis;
    private static RedisBreakerHintGateway gateway;
    private static LettuceConnectionFactory connectionFactory;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();

        var template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        gateway = new RedisBreakerHintGateway(template);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @Test
    void reportsNoHintBeforeAnyBreakerHasOpened() {
        assertThat(gateway.isHintedOpen("never-opened", "model")).isFalse();
    }

    @Test
    void reportsAHintAfterPublishOpen() {
        gateway.publishOpen("groq", "llama");

        assertThat(gateway.isHintedOpen("groq", "llama")).isTrue();
    }

    @Test
    void hintsAreScopedPerProviderAndModelIndependently() {
        gateway.publishOpen("gemini", "flash");

        assertThat(gateway.isHintedOpen("gemini", "flash")).isTrue();
        assertThat(gateway.isHintedOpen("gemini", "pro")).isFalse();
        assertThat(gateway.isHintedOpen("ollama", "flash")).isFalse();
    }
}
