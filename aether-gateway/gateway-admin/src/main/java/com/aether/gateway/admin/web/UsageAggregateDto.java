package com.aether.gateway.admin.web;

import java.math.BigDecimal;

public record UsageAggregateDto(String groupValue, long requestCount, long inputTokens, long outputTokens, BigDecimal costUsd, BigDecimal savedUsd) {
}
