package com.aether.gateway.core.domain;

import java.util.List;

/**
 * {@code toolCalls} is set on an assistant turn that asked for tools to be
 * run; {@code toolCallId} on a {@code role: "tool"} turn carrying one call's
 * result. An assistant turn that only calls tools has no text, which this
 * model represents as an empty {@code content} rather than null so every
 * existing content consumer (cache key, token estimate) keeps working.
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("a tool message must carry the tool_call_id it answers");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public boolean hasToolContent() {
        return !toolCalls.isEmpty() || toolCallId != null;
    }
}
