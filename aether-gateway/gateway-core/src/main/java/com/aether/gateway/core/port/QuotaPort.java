package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;

/**
 * F5: driven port for quota enforcement. gateway-router calls this and
 * never talks to Redis directly (ADR-006's javadoc note), which is what
 * lets the fail-closed unit tests run against a fake implementation with
 * no Redis at all.
 */
public interface QuotaPort {

    /**
     * F5.1 (RPS), F5.3 (concurrency), F5.2/F5.6 (monthly budget
     * check-and-reserve), all atomically per PRD section 12.2. On any
     * rejection, no state is left reserved (the adapter is responsible
     * for cleaning up any partial reservation, e.g. a concurrency slot
     * added before a later budget check failed).
     */
    QuotaDecision checkAndReserve(ApiKeyContext key, String requestId, long estimatedTokens);

    /** ADR-006: replaces the reservation with actual usage once known. */
    void reconcile(String keyId, String requestId, long actualTokens);

    /** Always called on request completion, success or failure, to free the concurrency slot. */
    void releaseConcurrencySlot(String keyId, String requestId);
}
