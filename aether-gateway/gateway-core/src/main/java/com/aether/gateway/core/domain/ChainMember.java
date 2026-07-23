package com.aether.gateway.core.domain;

/** One entry in a route's provider chain. {@code weight} null means "strict fallback, order-only". */
public record ChainMember(String provider, String model, Integer weight) {
}
