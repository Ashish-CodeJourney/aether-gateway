package com.aether.gateway.cache;

import com.aether.gateway.core.domain.CacheBypassRule;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.CacheKey;
import com.aether.gateway.core.domain.CacheThresholdGate;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.EntityNumericGuard;
import com.aether.gateway.core.port.CachePort;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * F4: orchestrates the exact-match (F4.1) and semantic (F4.2) lookup
 * paths, applying the bypass rule (F4.5), threshold gate (F4.2), and
 * entity/numeric guard (F4 correctness note) - all pure gateway-core
 * logic - against real Redis and pgvector state.
 *
 * <p>ADR-005: any failure talking to Redis, Postgres, or the embedding
 * model is caught here and turned into {@code CacheDecision.Miss}
 * rather than propagated, so a cache outage degrades to "always call
 * the provider," never an error response. This is the deliberate
 * opposite of {@code RedisQuotaAdapter}'s fail-closed contract.
 */
public class CacheAdapter implements CachePort {

    private static final int CANDIDATE_LIMIT = 5;

    private final RedisExactMatchStore redisStore;
    private final PgVectorCacheStore pgStore;
    private final EmbeddingGenerator embeddingGenerator;
    private final double configuredThreshold;

    public CacheAdapter(
            RedisExactMatchStore redisStore,
            PgVectorCacheStore pgStore,
            EmbeddingGenerator embeddingGenerator,
            double configuredThreshold) {
        this.redisStore = redisStore;
        this.pgStore = pgStore;
        this.embeddingGenerator = embeddingGenerator;
        this.configuredThreshold = configuredThreshold;
    }

    @Override
    public CacheDecision lookup(
            String namespace, ChatCompletionRequest request, Double similarityThresholdOverride, boolean noCacheHeaderPresent) {
        Optional<String> bypass = CacheBypassRule.bypassReason(request, noCacheHeaderPresent);
        if (bypass.isPresent()) {
            return new CacheDecision.Bypass(bypass.get());
        }

        try {
            return lookupUnsafe(namespace, request, similarityThresholdOverride);
        } catch (RuntimeException e) {
            // ADR-005: fail open. A cache outage must never turn into a
            // user-visible error; falling through to Miss means the
            // request still gets served, just without a cache hit.
            return new CacheDecision.Miss();
        }
    }

    private CacheDecision lookupUnsafe(String namespace, ChatCompletionRequest request, Double similarityThresholdOverride) {
        String exactHash = CacheKey.exactHash(request);

        Optional<String> redisHit = redisStore.get(namespace, exactHash);
        if (redisHit.isPresent()) {
            return new CacheDecision.ExactHit(exactHash, redisHit.get());
        }

        Optional<CacheEntryRow> pgExact = pgStore.findByExactHash(namespace, exactHash);
        if (pgExact.isPresent()) {
            CacheEntryRow row = pgExact.get();
            Duration remainingTtl = Duration.between(Instant.now(), row.expiresAt());
            if (!remainingTtl.isNegative()) {
                redisStore.put(namespace, exactHash, row.responseBodyJson(), remainingTtl);
            }
            return new CacheDecision.ExactHit(row.id(), row.responseBodyJson());
        }

        String candidateText = canonicalPromptText(request);
        float[] embedding = embeddingGenerator.embed(candidateText);
        Set<String> candidateFingerprint = EntityNumericGuard.fingerprint(candidateText);

        List<CacheEntryRow> candidates = pgStore.findNearest(namespace, embedding, CANDIDATE_LIMIT);
        for (CacheEntryRow candidate : candidates) {
            if (!CacheThresholdGate.isHit(candidate.similarity(), configuredThreshold, similarityThresholdOverride)) {
                break; // sorted most-similar first; no later candidate can pass either
            }
            if (!candidateFingerprint.equals(parseFingerprint(candidate.entityFingerprint()))) {
                continue; // guard rejects this candidate specifically, not the whole lookup
            }
            return new CacheDecision.SemanticHit(candidate.id(), candidate.responseBodyJson(), candidate.similarity());
        }
        return new CacheDecision.Miss();
    }

    @Override
    public void store(String namespace, ChatCompletionRequest request, String responseBodyJson, Duration ttl) {
        try {
            String exactHash = CacheKey.exactHash(request);
            String candidateText = canonicalPromptText(request);
            float[] embedding = embeddingGenerator.embed(candidateText);
            String fingerprint = String.join(",", new TreeSet<>(EntityNumericGuard.fingerprint(candidateText)));
            Instant expiresAt = Instant.now().plus(ttl);

            pgStore.insert(namespace, exactHash, embedding, candidateText, responseBodyJson, request.model(), fingerprint, expiresAt);
            redisStore.put(namespace, exactHash, responseBodyJson, ttl);
        } catch (RuntimeException e) {
            // Fail open: failing to write to cache is not a correctness
            // problem, only a missed future cost optimisation.
        }
    }

    @Override
    public long invalidateByPrefix(String namespace, String keyPrefix) {
        try {
            return pgStore.deleteByNamespaceAndModelPrefix(namespace, keyPrefix);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public long countEntries(String namespace) {
        try {
            return pgStore.countByNamespace(namespace);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Deliberately plain content only, no "role: " prefix: prefixing
     * broke {@link EntityNumericGuard}'s sentence-initial-stopword
     * detection, since the prompt's real first word was no longer at
     * the start of the string, causing phantom "entity:what"-style
     * tokens to appear whenever a message began with a common
     * interrogative like "What's ...". Joined with ". " rather than a
     * bare newline so each message still reads as starting a new
     * sentence for that same detection in multi-message conversations.
     */
    private String canonicalPromptText(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(ChatMessage::content)
                .collect(Collectors.joining(". "));
    }

    private Set<String> parseFingerprint(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(stored.split(",")));
    }
}
