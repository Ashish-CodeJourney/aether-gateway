package com.aether.gateway.router;

import com.aether.gateway.router.wire.WireModel;
import com.aether.gateway.router.wire.WireModelList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogMapperTest {

    @Test
    void mapsEveryWireModelToHealthyDomainModelInfo() {
        var wireList = new WireModelList("list", List.of(new WireModel("mock", "model"), new WireModel("mock2", "model")));

        var result = ModelCatalogMapper.toDomain(wireList);

        assertThat(result).extracting("id", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("mock", "healthy"),
                        org.assertj.core.groups.Tuple.tuple("mock2", "healthy"));
    }
}
