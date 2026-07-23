package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.RouteConfig;

import java.util.Map;

public record LoadedRoutingConfig(Map<String, ProviderConfig> providers, Map<String, RouteConfig> routes) {
    public LoadedRoutingConfig {
        providers = Map.copyOf(providers);
        routes = Map.copyOf(routes);
    }
}
