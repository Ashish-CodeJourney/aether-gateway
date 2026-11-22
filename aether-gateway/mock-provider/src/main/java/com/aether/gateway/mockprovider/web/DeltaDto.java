package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record DeltaDto(
        @JsonInclude(JsonInclude.Include.NON_NULL) String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCallDto> toolCalls) {
}
