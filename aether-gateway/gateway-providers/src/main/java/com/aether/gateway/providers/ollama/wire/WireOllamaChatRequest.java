package com.aether.gateway.providers.ollama.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** {@code tools} are OpenAI-format tool definitions, which Ollama accepts as they are. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireOllamaChatRequest(String model, List<WireOllamaMessage> messages, boolean stream, List<JsonNode> tools) {
}
