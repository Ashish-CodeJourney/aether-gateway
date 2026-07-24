package com.aether.gateway.core.domain;

import java.time.Duration;

/** F4.7: per-route cache settings from routing.yaml's optional {@code cache:} block (PRD section 7's example). */
public record RouteCacheConfig(boolean enabled, double threshold, Duration ttl) {

    public static final RouteCacheConfig DISABLED = new RouteCacheConfig(false, 1.0, Duration.ZERO);
}
