package com.aether.gateway.cache.inmemory;

import com.aether.gateway.cache.ExactMatchCacheContract;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.CacheMatching;
import com.aether.gateway.core.port.CachePort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryExactMatchCacheTest extends ExactMatchCacheContract {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final CacheMatching SEMANTIC = new CacheMatching.Semantic(0.5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
    private final InMemoryExactMatchCache cache = new InMemoryExactMatchCache(1_000, 1_000_000, clock);

    @Override
    protected CachePort cache() {
        return cache;
    }

    @Override
    protected void advanceClockBy(Duration duration) {
        clock.advance(duration);
    }

    @Test
    void aSemanticRouteIsServedExactMatchesOnlyAndNeverASemanticHit() {
        String namespace = freshNamespace();
        cache.store(namespace, requestFor("What is the capital of France?"), "{\"answer\":\"Paris\"}", TTL, SEMANTIC);

        assertThat(cache.lookup(namespace, requestFor("What is the capital of France?"), SEMANTIC, false))
                .isInstanceOf(CacheDecision.ExactHit.class);
        assertThat(cache.lookup(namespace, requestFor("Can you tell me France's capital city?"), SEMANTIC, false))
                .isEqualTo(new CacheDecision.Miss());
    }

    @Test
    void invalidatedEntriesAreNoLongerServed() {
        String namespace = freshNamespace();
        cache.store(namespace, requestFor("gpt-4o", "hello"), "{}", TTL, EXACT_ONLY);

        cache.invalidateByPrefix(namespace, "gpt");

        assertThat(cache.lookup(namespace, requestFor("gpt-4o", "hello"), EXACT_ONLY, false)).isEqualTo(new CacheDecision.Miss());
    }

    @Test
    void storingTheSameRequestAgainReplacesTheResponse() {
        String namespace = freshNamespace();
        cache.store(namespace, requestFor("hello"), "{\"v\":1}", TTL, EXACT_ONLY);

        cache.store(namespace, requestFor("hello"), "{\"v\":2}", TTL, EXACT_ONLY);

        assertThat(cache.lookup(namespace, requestFor("hello"), EXACT_ONLY, false))
                .isInstanceOfSatisfying(CacheDecision.ExactHit.class, hit -> assertThat(hit.responseBodyJson()).isEqualTo("{\"v\":2}"));
        assertThat(cache.countEntries(namespace)).isEqualTo(1);
    }

    @Test
    void beyondTheEntryCapTheLeastRecentlyUsedEntryIsEvicted() {
        var small = new InMemoryExactMatchCache(2, 1_000_000, clock);
        String namespace = freshNamespace();
        small.store(namespace, requestFor("a"), "{}", TTL, EXACT_ONLY);
        small.store(namespace, requestFor("b"), "{}", TTL, EXACT_ONLY);
        small.lookup(namespace, requestFor("a"), EXACT_ONLY, false);

        small.store(namespace, requestFor("c"), "{}", TTL, EXACT_ONLY);

        assertThat(small.lookup(namespace, requestFor("a"), EXACT_ONLY, false)).isInstanceOf(CacheDecision.ExactHit.class);
        assertThat(small.lookup(namespace, requestFor("b"), EXACT_ONLY, false)).isEqualTo(new CacheDecision.Miss());
        assertThat(small.lookup(namespace, requestFor("c"), EXACT_ONLY, false)).isInstanceOf(CacheDecision.ExactHit.class);
    }

    @Test
    void beyondTheByteCapTheOldestEntriesAreEvicted() {
        var small = new InMemoryExactMatchCache(100, 10, clock);
        String namespace = freshNamespace();
        small.store(namespace, requestFor("a"), "123456", TTL, EXACT_ONLY);

        small.store(namespace, requestFor("b"), "123456", TTL, EXACT_ONLY);

        assertThat(small.lookup(namespace, requestFor("a"), EXACT_ONLY, false)).isEqualTo(new CacheDecision.Miss());
        assertThat(small.lookup(namespace, requestFor("b"), EXACT_ONLY, false)).isInstanceOf(CacheDecision.ExactHit.class);
    }

    @Test
    void aResponseLargerThanTheWholeCacheIsNotStored() {
        var small = new InMemoryExactMatchCache(100, 10, clock);
        String namespace = freshNamespace();
        small.store(namespace, requestFor("a"), "1234", TTL, EXACT_ONLY);

        small.store(namespace, requestFor("huge"), "12345678901", TTL, EXACT_ONLY);

        assertThat(small.lookup(namespace, requestFor("huge"), EXACT_ONLY, false)).isEqualTo(new CacheDecision.Miss());
        assertThat(small.lookup(namespace, requestFor("a"), EXACT_ONLY, false)).isInstanceOf(CacheDecision.ExactHit.class);
    }
}
