package com.aether.gateway.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code index} is only present on a streamed {@code delta.tool_calls} fragment. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireToolCall(Integer index, String id, String type, WireFunctionCall function) {
}
