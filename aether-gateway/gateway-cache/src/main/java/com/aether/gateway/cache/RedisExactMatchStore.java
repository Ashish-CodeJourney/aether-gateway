package com.aether.gateway.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/** F4.1: hot exact-match cache ahead of Postgres, keyed by cache:exact:{ns}:{hash} (docs/design/redis-keys.md). */
public class RedisExactMatchStore {

    private final StringRedisTemplate redisTemplate;

    public RedisExactMatchStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String namespace, String exactHash) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(namespace, exactHash)));
    }

    public void put(String namespace, String exactHash, String responseBodyJson, Duration ttl) {
        redisTemplate.opsForValue().set(key(namespace, exactHash), responseBodyJson, ttl);
    }

    public void evict(String namespace, String exactHash) {
        redisTemplate.delete(key(namespace, exactHash));
    }

    private String key(String namespace, String exactHash) {
        return "cache:exact:" + namespace + ":" + exactHash;
    }
}
