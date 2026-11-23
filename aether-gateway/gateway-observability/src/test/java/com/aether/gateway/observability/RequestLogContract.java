package com.aether.gateway.observability;

import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.domain.UsageAggregate;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.core.port.RequestLogQueryPort;
import com.aether.gateway.core.port.UsageQueryPort;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F6.2 / F8.4: what every request log promises, whichever mode the
 * gateway runs in (ADR-010) - writing, reading the most recent entries
 * back, and aggregating usage. Characterised against Postgres in
 * integrationTest and run unchanged against the in-memory log. Writes
 * are fire-and-forget, so reads poll until the entries land.
 */
public abstract class RequestLogContract {

    public record RequestLogStore(RequestLogPort writer, RequestLogQueryPort query, UsageQueryPort usage) {
    }

    /** A store with no entries in it. */
    protected abstract RequestLogStore emptyStore();

    /** An API key id the store will accept entries for. */
    protected abstract UUID registeredApiKeyId();

    private static final RecursiveComparisonConfiguration MONEY_BY_VALUE = RecursiveComparisonConfiguration.builder()
            .withComparatorForType(Comparator.<BigDecimal>naturalOrder(), BigDecimal.class)
            .build();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private RequestLogStore store;

    @BeforeEach
    void startEmpty() {
        store = emptyStore();
    }

    private record Usage(String provider, String model, String route, Integer inputTokens, Integer outputTokens,
                         String costUsd, String savedUsd) {
    }

    private RequestLogEntry entry(UUID apiKeyId, Instant createdAt, Usage usage) {
        return new RequestLogEntry(
                UUID.randomUUID(), apiKeyId, null, usage.route(), usage.provider(), usage.model(),
                null, null, false, "MISS", null,
                usage.inputTokens(), usage.outputTokens(),
                usage.costUsd() == null ? null : new BigDecimal(usage.costUsd()),
                usage.savedUsd() == null ? null : new BigDecimal(usage.savedUsd()),
                10, 20, 1, List.of(), "OK", null, createdAt);
    }

    private RequestLogEntry entryAt(Instant createdAt) {
        return entry(registeredApiKeyId(), createdAt, new Usage("p", "m", "r", 1, 1, "0.000001", "0"));
    }

    private void logAndAwait(List<RequestLogEntry> entries) {
        entries.forEach(store.writer()::log);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (store.query().recent(1000).size() < entries.size()) {
            assertThat(System.nanoTime()).as("entries were not readable within 5s").isLessThan(deadline);
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aLoggedEntryIsReadBackWithEveryFieldIntact() {
        var logged = new RequestLogEntry(
                UUID.randomUUID(), registeredApiKeyId(), "trace-1", "mock", "mock-primary", "mock",
                UUID.randomUUID(), 3, true, "SEMANTIC_HIT", 0.5,
                12, 34, new BigDecimal("0.000123"), new BigDecimal("0.000045"),
                7, 89, 2, List.of("mock-primary", "mock-fallback"), "OK", null, NOW);

        logAndAwait(List.of(logged));

        assertThat(store.query().recent(10))
                .usingRecursiveFieldByFieldElementComparator(MONEY_BY_VALUE)
                .containsExactly(logged);
    }

    @Test
    void absentOptionalFieldsAreReadBackAbsent() {
        var logged = new RequestLogEntry(
                UUID.randomUUID(), registeredApiKeyId(), null, null, null, null,
                null, null, false, "BYPASS", null,
                null, null, null, null,
                null, null, null, List.of(), "FAILED", "provider_unavailable", NOW);

        logAndAwait(List.of(logged));

        assertThat(store.query().recent(10)).containsExactly(logged);
    }

    @Test
    void recentIsNewestFirstAndRespectsTheLimit() {
        var oldest = entryAt(NOW);
        var middle = entryAt(NOW.plusSeconds(1));
        var newest = entryAt(NOW.plusSeconds(2));

        logAndAwait(List.of(middle, newest, oldest));

        assertThat(store.query().recent(2)).extracting(RequestLogEntry::id).containsExactly(newest.id(), middle.id());
    }

    @Test
    void usageGroupsByModelSummingTokensAndCostsMostExpensiveFirst() {
        UUID key = registeredApiKeyId();
        logAndAwait(List.of(
                entry(key, NOW, new Usage("p", "cheap", "r", 10, 1, "0.000100", "0.000010")),
                entry(key, NOW, new Usage("p", "pricey", "r", 20, 2, "0.002000", "0")),
                entry(key, NOW, new Usage("p", "pricey", "r", 30, 3, "0.003000", "0.000500"))));

        List<UsageAggregate> usage = store.usage().query(NOW.minusSeconds(60), NOW.plusSeconds(60), "model");

        assertThat(usage)
                .usingRecursiveFieldByFieldElementComparator(MONEY_BY_VALUE)
                .containsExactly(
                        new UsageAggregate("pricey", 2, 50, 5, new BigDecimal("0.005"), new BigDecimal("0.0005")),
                        new UsageAggregate("cheap", 1, 10, 1, new BigDecimal("0.0001"), new BigDecimal("0.00001")));
    }

    @Test
    void usageCanBeGroupedByProviderRouteOrKey() {
        UUID keyA = registeredApiKeyId();
        UUID keyB = registeredApiKeyId();
        logAndAwait(List.of(
                entry(keyA, NOW, new Usage("openai", "m", "gpt", 1, 1, "0.000300", "0")),
                entry(keyB, NOW, new Usage("gemini", "m", "flash", 1, 1, "0.000100", "0"))));

        assertThat(store.usage().query(NOW.minusSeconds(60), NOW.plusSeconds(60), "provider"))
                .extracting(UsageAggregate::groupValue).containsExactly("openai", "gemini");
        assertThat(store.usage().query(NOW.minusSeconds(60), NOW.plusSeconds(60), "route"))
                .extracting(UsageAggregate::groupValue).containsExactly("gpt", "flash");
        assertThat(store.usage().query(NOW.minusSeconds(60), NOW.plusSeconds(60), "key"))
                .extracting(UsageAggregate::groupValue).containsExactly(keyA.toString(), keyB.toString());
    }

    @Test
    void usageCoversFromInclusiveToExclusive() {
        var atFrom = entryAt(NOW);
        var justBeforeTo = entryAt(NOW.plusSeconds(59));
        var atTo = entryAt(NOW.plusSeconds(60));
        logAndAwait(List.of(atFrom, justBeforeTo, atTo));

        List<UsageAggregate> usage = store.usage().query(NOW, NOW.plusSeconds(60), "model");

        assertThat(usage).singleElement().extracting(UsageAggregate::requestCount).isEqualTo(2L);
    }

    @Test
    void absentTokensAndCostsCountAsZero() {
        logAndAwait(List.of(entry(registeredApiKeyId(), NOW, new Usage("p", "m", "r", null, null, null, null))));

        UsageAggregate usage = store.usage().query(NOW.minusSeconds(60), NOW.plusSeconds(60), "model").getFirst();

        assertThat(usage.requestCount()).isEqualTo(1);
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(usage.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(usage.savedUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void anEmptyWindowHasNoUsage() {
        assertThat(store.usage().query(NOW, NOW.plusSeconds(60), "model")).isEmpty();
    }

    @Test
    void anUnknownGroupingIsRejected() {
        assertThatThrownBy(() -> store.usage().query(NOW, NOW.plusSeconds(60), "api_key_id; DROP TABLE request_log"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
