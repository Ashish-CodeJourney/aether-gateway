package com.aether.gateway.router;

import com.aether.gateway.core.domain.ModelInfo;
import com.aether.gateway.router.wire.WireModelList;

import java.util.List;

class ModelCatalogMapper {

    private ModelCatalogMapper() {
    }

    static List<ModelInfo> toDomain(WireModelList wireModelList) {
        return wireModelList.data().stream()
                .map(m -> new ModelInfo(m.id(), "healthy"))
                .toList();
    }
}
