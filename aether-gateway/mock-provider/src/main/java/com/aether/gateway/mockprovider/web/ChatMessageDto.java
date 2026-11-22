package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ChatMessageDto(
        String role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCallDto> toolCalls,
        @JsonInclude(JsonInclude.Include.NON_NULL) String toolCallId) {

    public ChatMessageDto(String role, String content) {
        this(role, content, null, null);
    }
}
