package com.aether.gateway.core.domain;

/**
 * F5: outcome of a quota check, made atomically (F5.5) before dispatch.
 * {@code Allowed} carries the reservation id needed to reconcile actual
 * usage afterward (ADR-006).
 */
public sealed interface QuotaDecision permits QuotaDecision.Allowed, QuotaDecision.Rejected {

    record Allowed(String requestId, long remainingMonthlyTokens, boolean nearMonthlyLimit) implements QuotaDecision {
    }

    /** {@code reason} is one of "rps_exceeded", "budget_exceeded", "concurrency_exceeded", or "quota_store_unavailable" (ADR-005 fail-closed). */
    record Rejected(String reason, long remainingMonthlyTokens) implements QuotaDecision {
    }
}
