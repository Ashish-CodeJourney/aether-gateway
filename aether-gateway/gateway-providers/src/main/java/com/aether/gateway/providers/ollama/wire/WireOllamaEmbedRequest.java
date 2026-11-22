package com.aether.gateway.providers.ollama.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Ollama's {@code POST /api/embed} request; {@code dimensions} omitted unless the caller asked for a specific size. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireOllamaEmbedRequest(String model, List<String> input, Integer dimensions) {
}
