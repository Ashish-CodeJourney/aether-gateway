package com.aether.gateway.providers.anthropic.wire;

/**
 * A single turn. Anthropic accepts only "user" and "assistant" here - a
 * system prompt is a top-level field, not a message. {@code content} is
 * either a plain string or a list of {@link WireAnthropicRequestBlock}s,
 * the latter only when a turn carries tool_use or tool_result blocks.
 */
public record WireAnthropicMessage(String role, Object content) {
}
