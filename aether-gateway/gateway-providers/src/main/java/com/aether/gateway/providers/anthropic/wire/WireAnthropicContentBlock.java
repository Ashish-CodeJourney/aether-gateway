package com.aether.gateway.providers.anthropic.wire;

/** Assistant output is a list of typed blocks; this gateway reads the {@code text} ones. */
public record WireAnthropicContentBlock(String type, String text) {
}
