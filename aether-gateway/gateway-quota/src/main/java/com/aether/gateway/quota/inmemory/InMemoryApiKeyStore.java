package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.quota.ApiKeyGenerator;
import com.aether.gateway.quota.ApiKeyHasher;

import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADR-010 lite mode: API keys held in process memory, lost on restart.
 * Like the Postgres store it keeps only the SHA-256 hash of each raw
 * key, never the key itself.
 */
public class InMemoryApiKeyStore implements ApiKeyLookupPort, ApiKeyAdminPort {

    private record StoredKey(ApiKeySummary summary, String keyHash, long sequence) {
        StoredKey withSummary(ApiKeySummary updated) {
            return new StoredKey(updated, keyHash, sequence);
        }
    }

    private static final Comparator<StoredKey> NEWEST_FIRST = Comparator
            .comparing((StoredKey k) -> k.summary().createdAt())
            .thenComparingLong(StoredKey::sequence)
            .reversed();

    private final InstantSource clock;
    private final Map<String, StoredKey> keysByHash = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public InMemoryApiKeyStore(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public CreatedApiKey create(String name, List<String> tags, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        String rawKey = ApiKeyGenerator.generateRawKey();
        return new CreatedApiKey(store(name, rawKey, tags, rpsLimit, concurrencyLimit, monthlyTokenBudget), rawKey);
    }

    /** Lite mode only: registers a raw key the operator chose up front, since keys created at runtime do not survive a restart. */
    public ApiKeySummary registerBootstrapKey(String name, String rawKey) {
        return store(name, rawKey, List.of(), null, null, null);
    }

    private ApiKeySummary store(String name, String rawKey, List<String> tags, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        var summary = new ApiKeySummary(
                UUID.randomUUID().toString(), name, ApiKeyGenerator.prefixOf(rawKey), List.copyOf(tags),
                rpsLimit, concurrencyLimit, monthlyTokenBudget, null, true, clock.instant());
        String hash = ApiKeyHasher.hash(rawKey);
        keysByHash.put(hash, new StoredKey(summary, hash, sequence.incrementAndGet()));
        return summary;
    }

    @Override
    public Optional<ApiKeyContext> findByRawKey(String rawKey) {
        return Optional.ofNullable(keysByHash.get(ApiKeyHasher.hash(rawKey)))
                .filter(k -> k.summary().enabled())
                .map(k -> new ApiKeyContext(
                        k.summary().id(), k.summary().rpsLimit(), k.summary().concurrencyLimit(), k.summary().monthlyTokenBudget()));
    }

    @Override
    public List<ApiKeySummary> list() {
        return keysByHash.values().stream().sorted(NEWEST_FIRST).map(StoredKey::summary).toList();
    }

    @Override
    public Optional<ApiKeySummary> update(String id, Boolean enabled, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        Optional<String> hash = hashOf(id);
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        StoredKey updated = keysByHash.computeIfPresent(hash.get(), (ignored, current) -> {
            ApiKeySummary s = current.summary();
            return current.withSummary(new ApiKeySummary(
                    s.id(), s.name(), s.keyPrefix(), s.tags(),
                    coalesce(rpsLimit, s.rpsLimit()),
                    coalesce(concurrencyLimit, s.concurrencyLimit()),
                    coalesce(monthlyTokenBudget, s.monthlyTokenBudget()),
                    s.monthlyUsdBudget(),
                    coalesce(enabled, s.enabled()),
                    s.createdAt()));
        });
        return Optional.ofNullable(updated).map(StoredKey::summary);
    }

    @Override
    public boolean delete(String id) {
        return hashOf(id).map(keysByHash::remove).isPresent();
    }

    private Optional<String> hashOf(String id) {
        return keysByHash.values().stream()
                .filter(k -> k.summary().id().equals(id))
                .map(StoredKey::keyHash)
                .findFirst();
    }

    private static <T> T coalesce(T supplied, T current) {
        return supplied != null ? supplied : current;
    }
}
