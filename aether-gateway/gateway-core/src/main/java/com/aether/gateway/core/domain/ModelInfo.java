package com.aether.gateway.core.domain;

/** {@code status} is "healthy" or "degraded" once breaker state exists (Phase 05); always "healthy" until then. */
public record ModelInfo(String id, String status) {
}
