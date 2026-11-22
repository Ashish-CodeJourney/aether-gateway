package com.aether.gateway.proxy.web;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * {@code tools} and {@code toolChoice} are bound as raw JSON trees, not
 * typed DTOs: the gateway relays tool calling rather than executing it,
 * so their JSON-schema payloads are handed to the domain as opaque JSON
 * text, unchanged.
 */
public record ChatCompletionRequestDto(
        String model,
        List<ChatMessageDto> messages,
        Boolean stream,
        Double temperature,
        List<JsonNode> tools,
        Map<String, String> variables,
        JsonNode toolChoice) {

    public ChatCompletionRequestDto(
            String model, List<ChatMessageDto> messages, Boolean stream, Double temperature, List<JsonNode> tools, Map<String, String> variables) {
        this(model, messages, stream, temperature, tools, variables, null);
    }
}
