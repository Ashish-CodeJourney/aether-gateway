package com.aether.gateway.providers.anthropic.wire;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code maxTokens} serialises to the required {@code max_tokens} field.
 * {@code system}, {@code tools} and {@code toolChoice} are null when the
 * caller sent none; the adapter's mapper omits nulls rather than sending
 * e.g. {@code "system": null}, which the API rejects.
 */
public record WireAnthropicRequest(
        String model,
        List<WireAnthropicMessage> messages,
        Integer maxTokens,
        String system,
        boolean stream,
        List<WireAnthropicTool> tools,
        JsonNode toolChoice) {
}
