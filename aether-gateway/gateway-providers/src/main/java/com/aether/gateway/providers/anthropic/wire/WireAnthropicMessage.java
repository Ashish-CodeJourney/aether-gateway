package com.aether.gateway.providers.anthropic.wire;

/** A single turn. Anthropic accepts only "user" and "assistant" here - a system prompt is a top-level field, not a message. */
public record WireAnthropicMessage(String role, String content) {
}
