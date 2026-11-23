package com.aether.gateway.observability.inmemory;

import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.observability.RequestLogContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRequestLogTest extends RequestLogContract {

    @Override
    protected RequestLogStore emptyStore() {
        var log = new InMemoryRequestLog(10_000);
        return new RequestLogStore(log, log, log);
    }

    @Override
    protected UUID registeredApiKeyId() {
        return UUID.randomUUID();
    }

    private static RequestLogEntry entryAt(Instant createdAt) {
        return new RequestLogEntry(
                UUID.randomUUID(), UUID.randomUUID(), null, "r", "p", "m", null, null, false, "MISS", null,
                1, 1, BigDecimal.ONE, BigDecimal.ZERO, 1, 1, 1, List.of(), "OK", null, createdAt);
    }

    @Test
    void onceFullTheOldestEntriesAreDropped() {
        var log = new InMemoryRequestLog(3);
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        List<RequestLogEntry> entries = IntStream.range(0, 5).mapToObj(i -> entryAt(start.plusSeconds(i))).toList();

        entries.forEach(log::log);

        assertThat(log.recent(10)).containsExactly(entries.get(4), entries.get(3), entries.get(2));
        assertThat(log.query(start, start.plusSeconds(60), "model"))
                .singleElement()
                .satisfies(usage -> assertThat(usage.requestCount()).isEqualTo(3));
    }

    @Test
    void aLimitLargerThanTheLogReturnsEverything() {
        var log = new InMemoryRequestLog(3);
        log.log(entryAt(Instant.parse("2026-11-01T00:00:00Z")));

        assertThat(log.recent(100)).hasSize(1);
    }
}
