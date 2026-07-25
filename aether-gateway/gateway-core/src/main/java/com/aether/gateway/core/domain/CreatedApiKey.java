package com.aether.gateway.core.domain;

/** F9.1: the raw key is returned exactly once, at creation time - never stored, never retrievable again. */
public record CreatedApiKey(ApiKeySummary summary, String rawKey) {
}
