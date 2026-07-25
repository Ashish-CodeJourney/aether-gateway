package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.RouteCacheConfig;
import com.aether.gateway.core.domain.RouteConfig;
import org.yaml.snakeyaml.Yaml;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F2.3: parses routing.yaml (PRD section 7's shape) into domain types.
 * Pure function over a YAML string, no file I/O, so it is unit-testable
 * without touching disk; {@link RoutingPolicyRepository} owns reading
 * the actual file and calling this.
 */
public class RoutingYamlParser {

    @SuppressWarnings("unchecked")
    public LoadedRoutingConfig parse(String yamlText) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlText);
        if (root == null) {
            return new LoadedRoutingConfig(Map.of(), Map.of());
        }

        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        Map<String, Object> providersRaw = (Map<String, Object>) root.getOrDefault("providers", Map.of());
        for (var entry : providersRaw.entrySet()) {
            Map<String, Object> providerMap = (Map<String, Object>) entry.getValue();
            String type = (String) providerMap.getOrDefault("type", "mock");
            String apiKeyEnvVar = (String) providerMap.get("apiKeyEnvVar");
            providers.put(entry.getKey(), new ProviderConfig(
                    entry.getKey(), (String) providerMap.get("baseUrl"), type, apiKeyEnvVar));
        }

        Map<String, RouteConfig> routes = new LinkedHashMap<>();
        List<Map<String, Object>> routesRaw = (List<Map<String, Object>>) root.getOrDefault("routes", List.of());
        for (Map<String, Object> routeMap : routesRaw) {
            String alias = (String) routeMap.get("alias");
            List<Map<String, Object>> chainRaw = (List<Map<String, Object>>) routeMap.get("chain");
            List<ChainMember> chain = chainRaw.stream()
                    .map(m -> new ChainMember(
                            (String) m.get("provider"),
                            (String) m.get("model"),
                            (Integer) m.get("weight")))
                    .toList();
            RouteCacheConfig cacheConfig = parseCacheConfig((Map<String, Object>) routeMap.get("cache"));
            routes.put(alias, new RouteConfig(alias, chain, cacheConfig));
        }

        return new LoadedRoutingConfig(providers, routes);
    }

    /** F4.7: routing.yaml's optional per-route {@code cache:} block (PRD section 7's example). Absent means disabled. */
    private RouteCacheConfig parseCacheConfig(Map<String, Object> cacheRaw) {
        if (cacheRaw == null) {
            return RouteCacheConfig.DISABLED;
        }
        boolean enabled = Boolean.TRUE.equals(cacheRaw.getOrDefault("enabled", Boolean.FALSE));
        double threshold = ((Number) cacheRaw.getOrDefault("threshold", 0.94)).doubleValue();
        Duration ttl = parseDuration((String) cacheRaw.getOrDefault("ttl", "6h"));
        return new RouteCacheConfig(enabled, threshold, ttl);
    }

    /** Supports routing.yaml's shorthand duration suffixes (s/m/h/d) as well as full ISO-8601 ("PT6H"). */
    private Duration parseDuration(String value) {
        if (value.startsWith("P") || value.startsWith("p")) {
            return Duration.parse(value);
        }
        char unit = value.charAt(value.length() - 1);
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unsupported TTL unit in '" + value + "'");
        };
    }
}
