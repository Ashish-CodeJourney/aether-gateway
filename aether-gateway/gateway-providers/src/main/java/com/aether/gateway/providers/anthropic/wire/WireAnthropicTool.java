package com.aether.gateway.providers.anthropic.wire;

import tools.jackson.databind.JsonNode;

/** Anthropic's tool definition: OpenAI's {@code function.parameters} JSON schema becomes {@code input_schema}. */
public record WireAnthropicTool(String name, String description, JsonNode inputSchema) {
}
