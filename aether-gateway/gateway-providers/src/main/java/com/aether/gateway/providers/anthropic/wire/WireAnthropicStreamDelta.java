package com.aether.gateway.providers.anthropic.wire;

/**
 * The {@code delta} of both {@code content_block_delta} (carrying
 * {@code text} for a text block, or {@code partial_json} for a tool_use
 * block's streamed input) and {@code message_delta} (carrying
 * {@code stop_reason}). One record covers all of them, since the fields
 * are disjoint and absent fields simply bind to null.
 */
public record WireAnthropicStreamDelta(String type, String text, String partialJson, String stopReason) {
}
