package com.aether.gateway.router;

import com.aether.gateway.core.domain.ModelInfo;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.router.routing.RoutingPolicyRepository;

import java.util.List;
import java.util.stream.Collectors;

/** F1.6: derives the model list from the loaded routing policy's route aliases, rather than querying each provider. */
public class RouterModelCatalog implements ModelCatalogUseCase {

    private final RoutingPolicyRepository routingPolicyRepository;

    public RouterModelCatalog(RoutingPolicyRepository routingPolicyRepository) {
        this.routingPolicyRepository = routingPolicyRepository;
    }

    @Override
    public List<ModelInfo> listModels() {
        return routingPolicyRepository.allRoutes().keySet().stream()
                .map(alias -> new ModelInfo(alias, "healthy"))
                .collect(Collectors.toList());
    }
}
