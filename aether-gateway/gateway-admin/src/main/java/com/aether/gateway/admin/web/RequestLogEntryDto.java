package com.aether.gateway.admin.web;

import java.math.BigDecimal;
import java.time.Instant;

public record RequestLogEntryDto(
        String id,
        String apiKeyId,
        String routeAlias,
        String provider,
        String model,
        boolean streamed,
        String cacheOutcome,
        Double similarity,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        BigDecimal savedUsd,
        Integer ttfbMs,
        Integer totalMs,
        String status,
        String errorCode,
        Instant createdAt) {
}
