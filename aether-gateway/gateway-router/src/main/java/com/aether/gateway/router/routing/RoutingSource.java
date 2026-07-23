package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.port.ProviderAdapter;

import java.util.Optional;

/** What {@link com.aether.gateway.router.resilience.ResilientRouter} needs from the routing policy; implemented by {@link RoutingPolicyRepository}, faked directly in tests. */
public interface RoutingSource {
    Optional<RouteConfig> routeFor(String alias);

    Optional<ProviderAdapter> adapterFor(String providerName);
}
