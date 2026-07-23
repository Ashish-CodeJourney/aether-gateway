package com.aether.gateway.router;

import com.aether.gateway.core.domain.ModelInfo;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.router.wire.WireModelList;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * M1's hardcoded {@link ModelCatalogUseCase}: aggregates the mock
 * provider only (F1.6). Real multi-provider aggregation arrives once
 * routing exists in Phase 05, and real providers in Phase 12.
 */
public class MockProviderModelCatalog implements ModelCatalogUseCase {

    private final RestClient restClient;

    public MockProviderModelCatalog(RestClient.Builder builder, String mockProviderBaseUrl) {
        var snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        this.restClient = builder
                .baseUrl(mockProviderBaseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(snakeCaseMapper));
                })
                .build();
    }

    @Override
    public List<ModelInfo> listModels() {
        WireModelList wireModelList = restClient.get()
                .uri("/v1/models")
                .retrieve()
                .body(WireModelList.class);
        return ModelCatalogMapper.toDomain(wireModelList);
    }
}
