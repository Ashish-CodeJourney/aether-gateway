package com.aether.gateway.router.resilience;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * ADR-004: publishes a 30-second-TTL advisory hint at
 * {@code cb:hint:{provider}:{model}} (PRD section 12.2) when a local
 * breaker opens, and lets other replicas fast-open based on it. Never
 * used to fast-close (see {@link ResilientRouter#breakerFor}, which only
 * ever calls {@code transitionToOpenState}, never a close transition,
 * from this gateway's {@code isHintedOpen} result).
 */
public class RedisBreakerHintGateway implements BreakerHintGateway {

    private static final Duration HINT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;

    public RedisBreakerHintGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publishOpen(String provider, String model) {
        redisTemplate.opsForValue().set(key(provider, model), "open", HINT_TTL);
    }

    @Override
    public boolean isHintedOpen(String provider, String model) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(provider, model)));
    }

    private String key(String provider, String model) {
        return "cb:hint:%s:%s".formatted(provider, model);
    }
}
