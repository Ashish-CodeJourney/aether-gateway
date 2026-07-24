package com.aether.gateway.quota;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/** F5.1: RPS token bucket, backed by lua/token_bucket.lua. */
public class RedisTokenBucket {

    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBucket(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryConsume(String keyId, int rpsLimit, long requestedTokens) {
        String key = "q:rps:" + keyId;
        Long result = redisTemplate.execute(
                LuaScripts.TOKEN_BUCKET,
                List.of(key),
                String.valueOf(rpsLimit),
                String.valueOf(rpsLimit),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(requestedTokens),
                String.valueOf(KEY_TTL.toSeconds()));
        return result != null && result == 1L;
    }
}
