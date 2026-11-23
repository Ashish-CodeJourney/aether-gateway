package com.aether.gateway.cache.inmemory;

import com.aether.gateway.core.domain.CacheBypassRule;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.CacheKey;
import com.aether.gateway.core.domain.CacheMatching;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.port.CachePort;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ADR-010 lite mode: F4.1 exact-match cache in process memory. Same
 * bypass rule (F4.5), same key (F4.1) and same per-namespace isolation
 * (F4.4) as the full-mode cache, but no semantic matching at all: the
 * embedding model is never loaded in lite mode, so a route asking for
 * semantic matching gets exact matches only and never a SEMANTIC_HIT.
 *
 * <p>Bounded twice over, by entry count and by total response bytes;
 * past either cap the least recently used entries go first. Entries
 * also expire with their TTL.
 */
public class InMemoryExactMatchCache implements CachePort {

    private record Key(String namespace, String exactHash) {
    }

    private record Entry(String model, String responseBodyJson, long bytes, Instant expiresAt) {
        boolean isLiveAt(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    private final int maxEntries;
    private final long maxBytes;
    private final InstantSource clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long totalBytes;

    public InMemoryExactMatchCache(int maxEntries, long maxBytes, InstantSource clock) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    @Override
    public CacheDecision lookup(String namespace, ChatCompletionRequest request, CacheMatching matching, boolean noCacheHeaderPresent) {
        Optional<String> bypass = CacheBypassRule.bypassReason(request, noCacheHeaderPresent);
        if (bypass.isPresent()) {
            return new CacheDecision.Bypass(bypass.get());
        }
        String exactHash = CacheKey.exactHash(request);
        return liveEntry(new Key(namespace, exactHash))
                .<CacheDecision>map(entry -> new CacheDecision.ExactHit(exactHash, entry.responseBodyJson()))
                .orElseGet(CacheDecision.Miss::new);
    }

    private synchronized Optional<Entry> liveEntry(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.isLiveAt(clock.instant())) {
            remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Override
    public synchronized void store(String namespace, ChatCompletionRequest request, String responseBodyJson, Duration ttl, CacheMatching matching) {
        long bytes = responseBodyJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            return;
        }
        Key key = new Key(namespace, CacheKey.exactHash(request));
        remove(key);
        entries.put(key, new Entry(request.model(), responseBodyJson, bytes, clock.instant().plus(ttl)));
        totalBytes += bytes;
        evictLeastRecentlyUsedBeyondCaps();
    }

    private void evictLeastRecentlyUsedBeyondCaps() {
        Iterator<Map.Entry<Key, Entry>> leastRecentFirst = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || totalBytes > maxBytes) && leastRecentFirst.hasNext()) {
            totalBytes -= leastRecentFirst.next().getValue().bytes();
            leastRecentFirst.remove();
        }
    }

    private void remove(Key key) {
        Entry removed = entries.remove(key);
        if (removed != null) {
            totalBytes -= removed.bytes();
        }
    }

    @Override
    public synchronized long invalidateByPrefix(String namespace, String keyPrefix) {
        List<Key> matching = entries.entrySet().stream()
                .filter(e -> e.getKey().namespace().equals(namespace) && e.getValue().model().startsWith(keyPrefix))
                .map(Map.Entry::getKey)
                .toList();
        matching.forEach(this::remove);
        return matching.size();
    }

    @Override
    public synchronized long countEntries(String namespace) {
        Instant now = clock.instant();
        return entries.entrySet().stream()
                .filter(e -> e.getKey().namespace().equals(namespace) && e.getValue().isLiveAt(now))
                .count();
    }
}
