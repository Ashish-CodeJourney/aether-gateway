package com.aether.gateway.providers.ollama.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** Unlike OpenAI's, {@code arguments} is a JSON object, not a string holding one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireOllamaFunctionCall(Integer index, String name, JsonNode arguments) {
}
