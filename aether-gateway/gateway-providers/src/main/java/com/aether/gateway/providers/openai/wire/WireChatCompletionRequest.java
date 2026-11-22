package com.aether.gateway.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code tools} and {@code toolChoice} are raw JSON trees relayed exactly
 * as the client sent them, and omitted entirely when absent: some
 * OpenAI-compatible servers reject an explicit {@code "tools": null}.
 */
public record WireChatCompletionRequest(
        String model,
        List<WireChatMessage> messages,
        boolean stream,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<JsonNode> tools,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode toolChoice) {
}
