package com.aether.gateway.core.domain;

/**
 * F6.1: the tag set and measurements recorded for one completed
 * request. {@code apiKeyId} is deliberately the raw id string (not
 * cardinality-limited further) - Phase 08's own risk note accepts this
 * as a known, non-blocking-for-now scale concern rather than a solved
 * problem.
 */
public record RequestMetrics(
        String provider,
        String model,
        String routeAlias,
        String apiKeyId,
        String cacheOutcome,
        String status,
        long totalMs,
        long inputTokens,
        long outputTokens,
        double costUsd,
        double savedUsd) {
}
