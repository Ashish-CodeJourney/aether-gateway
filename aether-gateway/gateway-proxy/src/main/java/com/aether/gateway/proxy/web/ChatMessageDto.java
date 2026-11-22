package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** {@code content} is always written, as null on a tool-call-only assistant turn, matching OpenAI's own responses. */
public record ChatMessageDto(
        String role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCallDto> toolCalls,
        @JsonInclude(JsonInclude.Include.NON_NULL) String toolCallId) {

    public ChatMessageDto(String role, String content) {
        this(role, content, null, null);
    }
}
