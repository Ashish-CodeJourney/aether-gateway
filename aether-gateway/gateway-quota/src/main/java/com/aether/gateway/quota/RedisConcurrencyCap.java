package com.aether.gateway.quota;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/** F5.3: concurrency cap, backed by concurrency_acquire.lua. */
public class RedisConcurrencyCap {

    private static final Duration KEY_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public RedisConcurrencyCap(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String keyId, int cap, String requestId) {
        Long result = redisTemplate.execute(
                LuaScripts.CONCURRENCY_ACQUIRE,
                List.of(concurrencyKey(keyId)),
                String.valueOf(cap),
                requestId,
                String.valueOf(KEY_TTL.toSeconds()));
        return result != null && result == 1L;
    }

    public void release(String keyId, String requestId) {
        redisTemplate.opsForSet().remove(concurrencyKey(keyId), requestId);
    }

    private String concurrencyKey(String keyId) {
        return "q:conc:" + keyId;
    }
}
