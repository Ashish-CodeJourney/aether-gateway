package com.aether.gateway.providers.ollama.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code id} is sent by recent Ollama versions (e.g. 0.24) and absent from older ones. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireOllamaToolCall(String id, String type, WireOllamaFunctionCall function) {
}
