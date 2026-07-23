package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ModelInfo;

import java.util.List;

/** F1.6: GET /v1/models. Aggregates the mock provider only until Phase 12 adds real providers. */
public interface ModelCatalogUseCase {
    List<ModelInfo> listModels();
}
