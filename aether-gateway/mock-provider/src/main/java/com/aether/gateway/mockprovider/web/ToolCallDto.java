package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code index} only on a streamed fragment; {@code id}/{@code type} only on a complete call or a stream's opening fragment. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallDto(Integer index, String id, String type, FunctionCallDto function) {
}
