package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.core.port.RoutingReloadUseCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * F2.7: hot-reloadable holder of the current routing policy and the
 * {@link ProviderAdapter} instances it wires up. gateway-router calls
 * {@link #reload()} in response to POST /admin/routes/reload; a fresh
 * read of the YAML file at {@code configPath} replaces the in-memory
 * policy atomically, with no restart.
 *
 * <p>Takes an {@code adapterFactory} rather than constructing a concrete
 * {@code ProviderAdapter} implementation itself: gateway-router depends
 * only on the port (ADR-001), never on gateway-providers directly. The
 * factory is supplied by gateway-proxy's wiring config, the one module
 * allowed to know about concrete adapters (see
 * docs/design/module-boundaries.md).
 */
public class RoutingPolicyRepository implements RoutingSource, RoutingReloadUseCase {

    private final Path configPath;
    private final Function<ProviderConfig, ProviderAdapter> adapterFactory;
    private final RoutingYamlParser parser = new RoutingYamlParser();
    private final ProviderBaseUrlValidator baseUrlValidator = new ProviderBaseUrlValidator();
    private final AtomicReference<LoadedRoutingConfig> config = new AtomicReference<>();
    private final AtomicReference<Map<String, ProviderAdapter>> adapters = new AtomicReference<>(Map.of());

    public RoutingPolicyRepository(Path configPath, Function<ProviderConfig, ProviderAdapter> adapterFactory) {
        this.configPath = configPath;
        this.adapterFactory = adapterFactory;
        reload();
    }

    public synchronized void reload() {
        try {
            String yaml = Files.readString(configPath);
            LoadedRoutingConfig loaded = parser.parse(yaml);

            // F9.3: validated before anything below is committed - a
            // rejected provider means this whole reload is rejected and
            // the previous (already-validated) config keeps serving, so
            // "no request is ever dispatched to that address" holds even
            // for a reload that mixes one bad provider in with good ones.
            // Scoped to real, operator-configured provider types only
            // ("type" other than the "mock" default) - mock-primary/
            // mock-fallback deliberately point at localhost for every
            // dev/test/docker-compose/kind setup this project has, and
            // that is not the SSRF risk F9.3 exists for (an operator
            // pointing a *real* provider at their own internal network).
            for (ProviderConfig providerConfig : loaded.providers().values()) {
                if (!"mock".equals(providerConfig.type())) {
                    baseUrlValidator.validate(providerConfig.name(), providerConfig.baseUrl());
                }
            }

            config.set(loaded);

            Map<String, ProviderAdapter> newAdapters = new HashMap<>();
            for (ProviderConfig providerConfig : loaded.providers().values()) {
                newAdapters.put(providerConfig.name(), adapterFactory.apply(providerConfig));
            }
            adapters.set(Map.copyOf(newAdapters));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load routing policy from " + configPath, e);
        }
    }

    public Optional<RouteConfig> routeFor(String alias) {
        return Optional.ofNullable(config.get().routes().get(alias));
    }

    public Optional<ProviderAdapter> adapterFor(String providerName) {
        return Optional.ofNullable(adapters.get().get(providerName));
    }

    public Map<String, RouteConfig> allRoutes() {
        return config.get().routes();
    }
}
