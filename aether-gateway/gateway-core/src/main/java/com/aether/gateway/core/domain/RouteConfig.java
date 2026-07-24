package com.aether.gateway.core.domain;

import java.util.List;

/** F2.3: a routing.yaml route entry, model alias to an ordered provider chain, plus F4.7's optional per-route cache config. */
public record RouteConfig(String alias, List<ChainMember> chain, RouteCacheConfig cache) {
    public RouteConfig {
        chain = List.copyOf(chain);
        if (cache == null) {
            cache = RouteCacheConfig.DISABLED;
        }
    }

    public RouteConfig(String alias, List<ChainMember> chain) {
        this(alias, chain, RouteCacheConfig.DISABLED);
    }
}
