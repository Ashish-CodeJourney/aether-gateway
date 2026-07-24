package com.aether.gateway.observability;

import com.aether.gateway.core.domain.ModelPricing;
import com.aether.gateway.core.port.CostModelPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F6.3: loads cost-model.yaml from disk, matching the "config change
 * requires no redeploy" non-functional requirement (PRD section 8) -
 * {@link #reload()} re-reads the file, same pattern as
 * RoutingPolicyRepository's F2.7 hot-reload.
 */
public class CostModelRepository implements CostModelPort {

    private final Path configPath;
    private final CostModelYamlParser parser;
    private final AtomicReference<Map<String, ModelPricing>> pricingByKey = new AtomicReference<>(Map.of());

    public CostModelRepository(Path configPath, CostModelYamlParser parser) {
        this.configPath = configPath;
        this.parser = parser;
        reload();
    }

    public synchronized void reload() {
        try {
            String yamlText = Files.readString(configPath);
            pricingByKey.set(parser.parse(yamlText));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load cost model from " + configPath, e);
        }
    }

    @Override
    public Optional<ModelPricing> pricingFor(String provider, String model) {
        return Optional.ofNullable(pricingByKey.get().get(CostModelYamlParser.key(provider, model)));
    }
}
