package com.aether.gateway.core.domain;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * F4.7: per-route cache settings from routing.yaml's optional {@code cache:} block (PRD section 7's example).
 * An empty {@code threshold} means the route defers to the global {@code aether.cache.default-threshold};
 * {@code semantic} false means exact-match caching only, with no embedding or similarity search.
 */
public record RouteCacheConfig(boolean enabled, OptionalDouble threshold, Duration ttl, boolean semantic) {

    public static final RouteCacheConfig DISABLED = new RouteCacheConfig(false, OptionalDouble.empty(), Duration.ZERO, false);
}
