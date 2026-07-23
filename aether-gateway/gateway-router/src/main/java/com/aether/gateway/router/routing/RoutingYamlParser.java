package com.aether.gateway.router.routing;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.RouteConfig;
import org.yaml.snakeyaml.Yaml;

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
            providers.put(entry.getKey(), new ProviderConfig(entry.getKey(), (String) providerMap.get("baseUrl")));
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
            routes.put(alias, new RouteConfig(alias, chain));
        }

        return new LoadedRoutingConfig(providers, routes);
    }
}
