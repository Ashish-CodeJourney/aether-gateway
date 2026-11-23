package com.aether.gateway.cache;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.CacheMatching;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.port.CachePort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4.1 / F4.4 / F4.5 / F4.7: what every cache promises for exact
 * matching, whichever mode the gateway runs in (ADR-010). Semantic
 * matching is full-mode only and is not part of this contract.
 * Characterised against Redis + Postgres in integrationTest and run
 * unchanged against the in-memory cache. Every test uses its own
 * namespace, so a shared store is fine.
 */
public abstract class ExactMatchCacheContract {

    protected static final CacheMatching EXACT_ONLY = new CacheMatching.ExactOnly();
    private static final Duration TTL = Duration.ofMinutes(10);

    protected abstract CachePort cache();

    /** Lets time pass for the cache under test: a real sleep against real infrastructure, a clock tick in memory. */
    protected abstract void advanceClockBy(Duration duration);

    protected static String freshNamespace() {
        return "ns-" + UUID.randomUUID();
    }

    protected static ChatCompletionRequest requestFor(String model, String prompt) {
        return new ChatCompletionRequest(model, List.of(new ChatMessage("user", prompt)), false);
    }

    protected static ChatCompletionRequest requestFor(String prompt) {
        return requestFor("mock", prompt);
    }

    @Test
    void anIdenticalRequestIsServedFromTheCacheOnceStored() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("What is the capital of France?"), "{\"answer\":\"Paris\"}", TTL, EXACT_ONLY);

        CacheDecision decision = cache().lookup(namespace, requestFor("What is the capital of France?"), EXACT_ONLY, false);

        assertThat(decision).isInstanceOfSatisfying(CacheDecision.ExactHit.class,
                hit -> assertThat(hit.responseBodyJson()).isEqualTo("{\"answer\":\"Paris\"}"));
    }

    @Test
    void nothingIsServedBeforeAnythingIsStored() {
        assertThat(cache().lookup(freshNamespace(), requestFor("Never asked before"), EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Miss());
    }

    @Test
    void whitespaceDifferencesStillMatch() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("How many  moons\n does Mars have?"), "{\"answer\":\"two\"}", TTL, EXACT_ONLY);

        assertThat(cache().lookup(namespace, requestFor("  How many moons does Mars have?"), EXACT_ONLY, false))
                .isInstanceOf(CacheDecision.ExactHit.class);
    }

    @Test
    void aDifferentPromptOrModelMisses() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("What is 2 + 2?"), "{\"answer\":\"4\"}", TTL, EXACT_ONLY);

        assertThat(cache().lookup(namespace, requestFor("What is 2 + 3?"), EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Miss());
        assertThat(cache().lookup(namespace, requestFor("other-model", "What is 2 + 2?"), EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Miss());
    }

    @Test
    void entriesNeverCrossNamespaces() {
        String tenantA = freshNamespace();
        cache().store(tenantA, requestFor("Summarise this contract"), "{\"answer\":\"a\"}", TTL, EXACT_ONLY);

        assertThat(cache().lookup(freshNamespace(), requestFor("Summarise this contract"), EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Miss());
    }

    @Test
    void theNoCacheHeaderBypassesTheCache() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("Cached prompt"), "{}", TTL, EXACT_ONLY);

        assertThat(cache().lookup(namespace, requestFor("Cached prompt"), EXACT_ONLY, true))
                .isEqualTo(new CacheDecision.Bypass("no_cache_header"));
    }

    @Test
    void aHighTemperatureBypassesTheCache() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "Be creative")), false, 0.9, null);

        assertThat(cache().lookup(freshNamespace(), request, EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Bypass("temperature_too_high"));
    }

    @Test
    void toolCallingBypassesTheCache() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "Weather?")), false, null,
                List.of("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}"));

        assertThat(cache().lookup(freshNamespace(), request, EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Bypass("tools_present"));
    }

    @Test
    void anExpiredEntryIsNeitherServedNorCounted() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("Short-lived"), "{}", Duration.ofSeconds(1), EXACT_ONLY);

        advanceClockBy(Duration.ofMillis(1500));

        assertThat(cache().lookup(namespace, requestFor("Short-lived"), EXACT_ONLY, false))
                .isEqualTo(new CacheDecision.Miss());
        assertThat(cache().countEntries(namespace)).isZero();
    }

    @Test
    void countEntriesCountsLiveEntriesPerNamespace() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("one"), "{}", TTL, EXACT_ONLY);
        cache().store(namespace, requestFor("two"), "{}", TTL, EXACT_ONLY);
        cache().store(freshNamespace(), requestFor("elsewhere"), "{}", TTL, EXACT_ONLY);

        assertThat(cache().countEntries(namespace)).isEqualTo(2);
        assertThat(cache().countEntries(freshNamespace())).isZero();
    }

    @Test
    void invalidatingByModelPrefixRemovesOnlyMatchingEntries() {
        String namespace = freshNamespace();
        cache().store(namespace, requestFor("gpt-4o-mini", "one"), "{}", TTL, EXACT_ONLY);
        cache().store(namespace, requestFor("gpt-4o", "two"), "{}", TTL, EXACT_ONLY);
        cache().store(namespace, requestFor("gemini-flash", "three"), "{}", TTL, EXACT_ONLY);

        long removed = cache().invalidateByPrefix(namespace, "gpt-");

        assertThat(removed).isEqualTo(2);
        assertThat(cache().countEntries(namespace)).isEqualTo(1);
    }
}
