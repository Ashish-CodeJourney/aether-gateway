package com.aether.gateway.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** F8.1: the admin-facing view of an api_key row - never the hash. */
public record ApiKeySummary(
        String id,
        String name,
        String keyPrefix,
        List<String> tags,
        Integer rpsLimit,
        Integer concurrencyLimit,
        Long monthlyTokenBudget,
        BigDecimal monthlyUsdBudget,
        boolean enabled,
        Instant createdAt) {
}
