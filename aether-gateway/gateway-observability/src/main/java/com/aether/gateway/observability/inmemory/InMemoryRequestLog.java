package com.aether.gateway.observability.inmemory;

import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.domain.UsageAggregate;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.core.port.RequestLogQueryPort;
import com.aether.gateway.core.port.UsageQueryPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ADR-010 lite mode: the request log as a bounded ring buffer in process
 * memory. Once full, every new entry drops the oldest, so the request
 * explorer and {@code /admin/usage} only ever see the most recent
 * {@code capacity} requests, and nothing survives a restart. Logging is
 * a constant-time append under a short lock, so it never holds up the
 * request it describes (F6.2).
 */
public class InMemoryRequestLog implements RequestLogPort, RequestLogQueryPort, UsageQueryPort {

    private static final Map<String, Function<RequestLogEntry, Object>> GROUPINGS = Map.of(
            "key", RequestLogEntry::apiKeyId,
            "provider", RequestLogEntry::provider,
            "model", RequestLogEntry::model,
            "route", RequestLogEntry::routeAlias);

    private final int capacity;
    private final Deque<RequestLogEntry> entries;

    public InMemoryRequestLog(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void log(RequestLogEntry entry) {
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    private synchronized List<RequestLogEntry> snapshot() {
        return List.copyOf(entries);
    }

    @Override
    public List<RequestLogEntry> recent(int limit) {
        return snapshot().stream()
                .sorted(Comparator.comparing(RequestLogEntry::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<UsageAggregate> query(Instant from, Instant to, String groupBy) {
        Function<RequestLogEntry, Object> grouping = GROUPINGS.get(groupBy);
        if (grouping == null) {
            throw new IllegalArgumentException("Unknown groupBy value: " + groupBy + " (expected one of " + GROUPINGS.keySet() + ")");
        }
        return snapshot().stream()
                .filter(e -> !e.createdAt().isBefore(from) && e.createdAt().isBefore(to))
                .collect(Collectors.groupingBy(e -> String.valueOf(grouping.apply(e))))
                .entrySet().stream()
                .map(group -> aggregate(group.getKey(), group.getValue()))
                .sorted(Comparator.comparing(UsageAggregate::costUsd).reversed())
                .toList();
    }

    private static UsageAggregate aggregate(String groupValue, List<RequestLogEntry> group) {
        return new UsageAggregate(
                groupValue,
                group.size(),
                group.stream().mapToLong(e -> Objects.requireNonNullElse(e.inputTokens(), 0)).sum(),
                group.stream().mapToLong(e -> Objects.requireNonNullElse(e.outputTokens(), 0)).sum(),
                sum(group, RequestLogEntry::costUsd),
                sum(group, RequestLogEntry::savedUsd));
    }

    private static BigDecimal sum(List<RequestLogEntry> group, Function<RequestLogEntry, BigDecimal> amount) {
        return group.stream().map(amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
