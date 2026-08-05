package com.aether.gateway.providers.anthropic.wire;

/**
 * The {@code delta} of both {@code content_block_delta} (carrying
 * {@code text}) and {@code message_delta} (carrying {@code stop_reason}).
 * One record covers both, since the fields are disjoint and absent
 * fields simply bind to null.
 */
public record WireAnthropicStreamDelta(String type, String text, String stopReason) {
}
