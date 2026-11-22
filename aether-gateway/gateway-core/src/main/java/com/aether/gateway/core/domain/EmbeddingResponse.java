package com.aether.gateway.core.domain;

import java.util.List;

/** Embeddings have no completion tokens, so usage is prompt tokens alone (OpenAI reports total_tokens == prompt_tokens). */
public record EmbeddingResponse(String model, List<Embedding> data, int promptTokens) {
    public EmbeddingResponse {
        data = List.copyOf(data);
    }
}
