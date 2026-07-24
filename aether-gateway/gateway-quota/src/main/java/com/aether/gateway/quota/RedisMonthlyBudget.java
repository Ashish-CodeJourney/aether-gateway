package com.aether.gateway.quota;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** F5.2 / F5.6 / ADR-006: monthly token budget with reservation and reconciliation, backed by budget_reserve.lua / budget_reconcile.lua. */
public class RedisMonthlyBudget {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(5);
    private static final Duration MONTHLY_COUNTER_TTL = Duration.ofDays(40);

    private final StringRedisTemplate redisTemplate;

    public RedisMonthlyBudget(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record ReservationResult(boolean accepted, long totalAfter) {
    }

    @SuppressWarnings("unchecked")
    public ReservationResult checkAndReserve(String keyId, String requestId, long budget, long estimatedTokens) {
        String monthlyKey = monthlyKey(keyId);
        String reservationKey = reservationKey(requestId);

        List<Long> result = (List<Long>) redisTemplate.execute(
                LuaScripts.BUDGET_RESERVE,
                List.of(monthlyKey, reservationKey),
                String.valueOf(budget),
                String.valueOf(estimatedTokens),
                String.valueOf(RESERVATION_TTL.toSeconds()),
                String.valueOf(MONTHLY_COUNTER_TTL.toSeconds()));

        boolean accepted = result.get(0) == 1L;
        return new ReservationResult(accepted, result.get(1));
    }

    public void reconcile(String keyId, String requestId, long actualTokens) {
        redisTemplate.execute(
                LuaScripts.BUDGET_RECONCILE,
                List.of(monthlyKey(keyId), reservationKey(requestId)),
                String.valueOf(actualTokens));
    }

    public long currentUsage(String keyId) {
        String value = redisTemplate.opsForValue().get(monthlyKey(keyId));
        return value == null ? 0 : Long.parseLong(value);
    }

    private String monthlyKey(String keyId) {
        return "q:tokens:" + keyId + ":" + YYYYMM.format(Instant.now());
    }

    private String reservationKey(String requestId) {
        return "q:reserved:" + requestId;
    }
}
