package com.aether.gateway.providers.ollama.wire;

import java.util.List;

/** Ollama's {@code POST /api/embed} response: one vector per input, in input order. */
public record WireOllamaEmbedResponse(String model, List<List<Float>> embeddings, Integer promptEvalCount) {
}
