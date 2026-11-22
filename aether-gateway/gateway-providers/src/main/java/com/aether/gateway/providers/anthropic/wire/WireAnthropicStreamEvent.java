package com.aether.gateway.providers.anthropic.wire;

/**
 * One SSE event from the Messages API stream. {@code type} discriminates
 * message_start / content_block_start / ping / content_block_delta /
 * content_block_stop / message_delta / message_stop. content_block_delta
 * carries text or tool input, content_block_start introduces a tool_use
 * block via {@code contentBlock}, message_delta carries the stop reason,
 * and only message_stop terminates. Token usage arrives in two halves:
 * the input count on message_start's {@code message}, the cumulative
 * output count on message_delta's top-level {@code usage}.
 */
public record WireAnthropicStreamEvent(
        String type,
        Integer index,
        WireAnthropicStreamDelta delta,
        WireAnthropicContentBlock contentBlock,
        WireAnthropicResponse message,
        WireAnthropicUsage usage) {
}
