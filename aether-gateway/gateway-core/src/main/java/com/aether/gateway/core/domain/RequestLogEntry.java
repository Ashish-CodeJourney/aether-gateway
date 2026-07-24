package com.aether.gateway.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F6.2: one row of the structured request log, matching db/migrations' request_log table exactly. */
public record RequestLogEntry(
        UUID id,
        UUID apiKeyId,
        String traceId,
        String routeAlias,
        String provider,
        String model,
        UUID promptId,
        Integer promptVersion,
        boolean streamed,
        String cacheOutcome,
        Double similarity,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        BigDecimal savedUsd,
        Integer ttfbMs,
        Integer totalMs,
        Integer attemptCount,
        List<String> failoverChain,
        String status,
        String errorCode,
        Instant createdAt) {
    public RequestLogEntry {
        failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
    }
}
