package com.aether.gateway.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record WireChatMessage(
        String role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WireToolCall> toolCalls,
        @JsonInclude(JsonInclude.Include.NON_NULL) String toolCallId) {
}
