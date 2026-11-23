package com.aether.gateway.providers.ollama.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code toolCalls} is set on an assistant turn that called tools;
 * {@code toolName} (documented) and {@code toolCallId} on a
 * {@code role: "tool"} result. Ollama ignores fields it does not know, so
 * {@code toolCallId} is harmless on versions that match results by name
 * alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireOllamaMessage(String role, String content, List<WireOllamaToolCall> toolCalls, String toolName, String toolCallId) {

    public WireOllamaMessage(String role, String content) {
        this(role, content, null, null, null);
    }
}
