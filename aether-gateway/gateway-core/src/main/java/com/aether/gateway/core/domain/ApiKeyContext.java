package com.aether.gateway.core.domain;

/** F8.1 (partial, read-only in M3): the subset of api_key columns quota enforcement needs. */
public record ApiKeyContext(
        String keyId,
        Integer rpsLimit,
        Integer concurrencyLimit,
        Long monthlyTokenBudget) {
}
