package com.aether.gateway.admin.web;

/** {@code rawKey} is present only in this create-time response - never retrievable again (F9.1). */
public record CreatedApiKeyResponseDto(ApiKeySummaryDto summary, String rawKey) {
}
