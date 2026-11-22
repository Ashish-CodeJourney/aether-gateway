package com.aether.gateway.providers.mock.wire;

import java.util.List;

public record WireEmbeddingResponse(String object, List<WireEmbeddingData> data, String model, WireEmbeddingUsage usage) {
}
