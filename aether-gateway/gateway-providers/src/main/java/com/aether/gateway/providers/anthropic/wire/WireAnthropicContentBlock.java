package com.aether.gateway.providers.anthropic.wire;

import tools.jackson.databind.JsonNode;

/**
 * An inbound content block - in a response's {@code content} or a stream's
 * {@code content_block_start}. This gateway reads {@code text} blocks and
 * {@code tool_use} blocks ({@code id}, {@code name}, {@code input}).
 */
public record WireAnthropicContentBlock(String type, String text, String id, String name, JsonNode input) {
}
