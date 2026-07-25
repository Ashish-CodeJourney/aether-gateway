package com.aether.gateway.admin.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ApiKeySummaryDto(
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
