package com.aether.gateway.observability;

import com.aether.gateway.core.domain.ModelPricing;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F6.3: parses cost-model.yaml (a flat list of per-(provider, model)
 * prices) into domain types. Pure function over a YAML string, no file
 * I/O, mirroring RoutingYamlParser's shape in gateway-router.
 */
public class CostModelYamlParser {

    @SuppressWarnings("unchecked")
    public Map<String, ModelPricing> parse(String yamlText) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlText);
        Map<String, ModelPricing> pricingByKey = new LinkedHashMap<>();
        if (root == null) {
            return pricingByKey;
        }

        List<Map<String, Object>> modelsRaw = (List<Map<String, Object>>) root.getOrDefault("models", List.of());
        for (Map<String, Object> entry : modelsRaw) {
            String provider = (String) entry.get("provider");
            String model = (String) entry.get("model");
            BigDecimal inputPrice = toBigDecimal(entry.get("inputPricePerMillion"));
            BigDecimal outputPrice = toBigDecimal(entry.get("outputPricePerMillion"));
            var pricing = new ModelPricing(provider, model, inputPrice, outputPrice);
            pricingByKey.put(key(provider, model), pricing);
        }
        return pricingByKey;
    }

    static String key(String provider, String model) {
        return provider + ":" + model;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }
}
