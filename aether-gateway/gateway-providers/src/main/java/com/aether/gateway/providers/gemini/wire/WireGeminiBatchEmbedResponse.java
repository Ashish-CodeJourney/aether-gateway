package com.aether.gateway.providers.gemini.wire;

import java.util.List;

public record WireGeminiBatchEmbedResponse(List<WireGeminiEmbeddingValues> embeddings) {
}
