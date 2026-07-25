package com.aether.gateway.core.domain;

import java.math.BigDecimal;

/** F8.4: one grouped row of {@code GET /admin/usage}. */
public record UsageAggregate(String groupValue, long requestCount, long inputTokens, long outputTokens, BigDecimal costUsd, BigDecimal savedUsd) {
}
