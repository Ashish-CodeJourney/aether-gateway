package com.aether.gateway.quota;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/** Loads the Lua scripts (gateway-quota/src/main/resources/lua) as Redis-executable scripts, once, at class init. */
final class LuaScripts {

    static final RedisScript<Long> TOKEN_BUCKET = load("lua/token_bucket.lua", Long.class);
    static final RedisScript<List> BUDGET_RESERVE = load("lua/budget_reserve.lua", List.class);
    static final RedisScript<Long> BUDGET_RECONCILE = load("lua/budget_reconcile.lua", Long.class);
    static final RedisScript<Long> CONCURRENCY_ACQUIRE = load("lua/concurrency_acquire.lua", Long.class);

    private LuaScripts() {
    }

    private static <T> RedisScript<T> load(String classpathLocation, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(resultType);
        return script;
    }
}
