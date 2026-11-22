package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Serves both OpenAI tool-call shapes: a complete call in
 * {@code message.tool_calls} (no {@code index}) and a streamed fragment in
 * {@code delta.tool_calls} (always an {@code index}, other fields only on
 * the fragment that introduces them).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallDto(Integer index, String id, String type, FunctionCallDto function) {
}
