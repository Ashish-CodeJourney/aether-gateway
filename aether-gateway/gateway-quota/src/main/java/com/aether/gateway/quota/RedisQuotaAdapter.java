package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.SoftLimitCalculator;
import com.aether.gateway.core.port.QuotaPort;
import org.springframework.dao.DataAccessException;

/**
 * F5: combines the RPS token bucket, concurrency cap, and monthly budget
 * reservation into one {@link QuotaPort}. Each concern's own Lua script
 * is independently atomic (per PRD section 12.2); this class sequences
 * them (RPS, then concurrency, then budget) and rolls back any earlier
 * acquisition if a later check rejects, so no state is left reserved on
 * a net rejection.
 *
 * <p>ADR-005: if Redis is unavailable, every check fails closed (reject
 * the request) rather than allowing it through unmetered.
 */
public class RedisQuotaAdapter implements QuotaPort {

    private final RedisTokenBucket tokenBucket;
    private final RedisConcurrencyCap concurrencyCap;
    private final RedisMonthlyBudget monthlyBudget;

    public RedisQuotaAdapter(RedisTokenBucket tokenBucket, RedisConcurrencyCap concurrencyCap, RedisMonthlyBudget monthlyBudget) {
        this.tokenBucket = tokenBucket;
        this.concurrencyCap = concurrencyCap;
        this.monthlyBudget = monthlyBudget;
    }

    @Override
    public QuotaDecision checkAndReserve(ApiKeyContext key, String requestId, long estimatedTokens) {
        try {
            if (key.rpsLimit() != null && !tokenBucket.tryConsume(key.keyId(), key.rpsLimit(), 1)) {
                return new QuotaDecision.Rejected("rps_exceeded", monthlyBudget.currentUsage(key.keyId()));
            }

            boolean concurrencyAcquired = key.concurrencyLimit() == null
                    || concurrencyCap.tryAcquire(key.keyId(), key.concurrencyLimit(), requestId);
            if (!concurrencyAcquired) {
                return new QuotaDecision.Rejected("concurrency_exceeded", monthlyBudget.currentUsage(key.keyId()));
            }

            if (key.monthlyTokenBudget() == null) {
                return new QuotaDecision.Allowed(requestId, Long.MAX_VALUE, false);
            }

            var reservation = monthlyBudget.checkAndReserve(key.keyId(), requestId, key.monthlyTokenBudget(), estimatedTokens);
            if (!reservation.accepted()) {
                if (key.concurrencyLimit() != null) {
                    concurrencyCap.release(key.keyId(), requestId);
                }
                long remaining = Math.max(0, key.monthlyTokenBudget() - reservation.totalAfter());
                return new QuotaDecision.Rejected("budget_exceeded", remaining);
            }

            long remaining = Math.max(0, key.monthlyTokenBudget() - reservation.totalAfter());
            boolean nearLimit = SoftLimitCalculator.isNearMonthlyLimit(remaining, key.monthlyTokenBudget());
            return new QuotaDecision.Allowed(requestId, remaining, nearLimit);
        } catch (DataAccessException e) {
            // ADR-005: fail closed. Best-effort cleanup of any partial
            // acquisition; if that also fails, the TTLs on q:conc/q:rps
            // entries are the safety net.
            try {
                if (key.concurrencyLimit() != null) {
                    concurrencyCap.release(key.keyId(), requestId);
                }
            } catch (DataAccessException ignored) {
                // Redis is already down; nothing more to do here.
            }
            return new QuotaDecision.Rejected("quota_store_unavailable", 0);
        }
    }

    @Override
    public void reconcile(String keyId, String requestId, long actualTokens) {
        try {
            monthlyBudget.reconcile(keyId, requestId, actualTokens);
        } catch (DataAccessException e) {
            // Non-fatal: the reservation's own TTL (5 minutes) is the
            // safety net if reconciliation cannot complete right now.
        }
    }

    @Override
    public void releaseConcurrencySlot(String keyId, String requestId) {
        try {
            concurrencyCap.release(keyId, requestId);
        } catch (DataAccessException e) {
            // Non-fatal: the concurrency set entry's own TTL is the safety net.
        }
    }
}
