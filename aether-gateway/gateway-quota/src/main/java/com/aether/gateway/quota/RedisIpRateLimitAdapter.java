package com.aether.gateway.quota;

import com.aether.gateway.core.port.IpRateLimitPort;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * F9.5: per-IP rate limit for unauthenticated requests, reusing
 * {@link RedisTokenBucket} - the same Lua token-bucket script the
 * per-API-key RPS limit (F5.1) already uses, just keyed by IP address
 * under a distinct namespace ("ip:" prefix passed as the bucket's
 * keyId) instead of an API key ID, so the two limiters never collide.
 */
public class RedisIpRateLimitAdapter implements IpRateLimitPort {

    private final RedisTokenBucket tokenBucket;
    private final int requestsPerSecond;

    public RedisIpRateLimitAdapter(StringRedisTemplate redisTemplate, int requestsPerSecond) {
        this.tokenBucket = new RedisTokenBucket(redisTemplate);
        this.requestsPerSecond = requestsPerSecond;
    }

    @Override
    public boolean tryConsume(String ipAddress) {
        return tokenBucket.tryConsume("ip:" + ipAddress, requestsPerSecond, 1);
    }
}
