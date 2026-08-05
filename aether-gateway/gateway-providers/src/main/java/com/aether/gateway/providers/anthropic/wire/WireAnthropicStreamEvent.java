package com.aether.gateway.providers.anthropic.wire;

/**
 * One SSE event from the Messages API stream. {@code type} discriminates
 * message_start / content_block_start / ping / content_block_delta /
 * content_block_stop / message_delta / message_stop; only
 * content_block_delta carries text and only message_stop terminates.
 */
public record WireAnthropicStreamEvent(String type, Integer index, WireAnthropicStreamDelta delta) {
}
