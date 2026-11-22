package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record DeltaDto(
        @JsonInclude(JsonInclude.Include.NON_NULL) String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCallDto> toolCalls) {

    public DeltaDto(String content) {
        this(content, null);
    }
}
