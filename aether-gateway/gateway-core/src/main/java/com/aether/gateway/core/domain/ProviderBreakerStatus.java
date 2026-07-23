package com.aether.gateway.core.domain;

/** F3.8: breaker state observability, keyed by (provider, model) per F3.1. */
public record ProviderBreakerStatus(String provider, String model, BreakerState state) {
}
