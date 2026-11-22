package com.aether.gateway.providers.anthropic.wire;

import tools.jackson.databind.JsonNode;

/**
 * One outbound content block: {@code text}, {@code tool_use} (an earlier
 * assistant tool call being replayed) or {@code tool_result}. Fields not
 * used by a block's type stay null and are omitted by the adapter's mapper.
 */
public record WireAnthropicRequestBlock(String type, String text, String id, String name, JsonNode input, String toolUseId, String content) {

    public static WireAnthropicRequestBlock text(String text) {
        return new WireAnthropicRequestBlock("text", text, null, null, null, null, null);
    }

    public static WireAnthropicRequestBlock toolUse(String id, String name, JsonNode input) {
        return new WireAnthropicRequestBlock("tool_use", null, id, name, input, null, null);
    }

    public static WireAnthropicRequestBlock toolResult(String toolUseId, String content) {
        return new WireAnthropicRequestBlock("tool_result", null, null, null, null, toolUseId, content);
    }
}
