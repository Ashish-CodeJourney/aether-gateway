package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChatCompletionRequest;

import java.time.Duration;

/**
 * F4: driven port for the cache subsystem. gateway-proxy calls this and
 * never talks to Redis/pgvector/the embedding model directly (ADR-009).
 * The entity/numeric guard and threshold gate are pure gateway-core
 * logic (see {@code EntityNumericGuard}, {@code CacheThresholdGate}),
 * but the adapter is responsible for invoking them against each
 * candidate before returning a {@code SemanticHit}, since only the
 * adapter has the stored entry's original prompt text to compare
 * against.
 *
 * <p>ADR-005: cache unavailable (Redis or Postgres down) means fail
 * open - the adapter must return {@code CacheDecision.Miss} rather than
 * throwing, letting the request proceed to the provider. This is the
 * opposite of {@code QuotaPort}'s fail-closed contract.
 */
public interface CachePort {

    CacheDecision lookup(String namespace, ChatCompletionRequest request, Double similarityThresholdOverride, boolean noCacheHeaderPresent);

    void store(String namespace, ChatCompletionRequest request, String responseBodyJson, Duration ttl);

    /** F4.7: manual invalidation by key prefix within a namespace. Returns the number of entries removed. */
    long invalidateByPrefix(String namespace, String keyPrefix);

    /** F8.3: {@code GET /admin/cache/stats} - the number of live (non-expired) entries in a namespace. */
    long countEntries(String namespace);
}
