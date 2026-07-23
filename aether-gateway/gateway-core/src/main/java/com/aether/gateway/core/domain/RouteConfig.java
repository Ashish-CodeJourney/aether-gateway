package com.aether.gateway.core.domain;

import java.util.List;

/** F2.3: a routing.yaml route entry, model alias to an ordered provider chain. */
public record RouteConfig(String alias, List<ChainMember> chain) {
    public RouteConfig {
        chain = List.copyOf(chain);
    }
}
