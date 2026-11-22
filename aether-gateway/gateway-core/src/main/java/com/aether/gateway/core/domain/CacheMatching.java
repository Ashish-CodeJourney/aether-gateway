package com.aether.gateway.core.domain;

/**
 * F4.1/F4.2: how a cached route matches a request against stored
 * entries - exact hash only, or exact hash first and then embedding
 * similarity at a threshold (routing.yaml's {@code cache.semantic}).
 */
public sealed interface CacheMatching permits CacheMatching.ExactOnly, CacheMatching.Semantic {

    static CacheMatching forRoute(RouteCacheConfig route, double similarityThreshold) {
        return route.semantic() ? new Semantic(similarityThreshold) : new ExactOnly();
    }

    record ExactOnly() implements CacheMatching {
    }

    record Semantic(double similarityThreshold) implements CacheMatching {
    }
}
