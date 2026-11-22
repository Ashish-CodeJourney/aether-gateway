package com.aether.gateway.core.domain;

import java.util.List;

/**
 * {@code tools} holds each OpenAI-format tool definition, and
 * {@code toolChoice} the {@code tool_choice} value, as opaque JSON text:
 * this project relays tool calling rather than executing it, and keeping
 * the JSON-schema payloads unparsed means gateway-core needs no JSON
 * library and an adapter that passes them through can do so verbatim.
 * Adapters that must translate them (e.g. Anthropic) parse them at the
 * edge.
 */
public record ChatCompletionRequest(
        String model, List<ChatMessage> messages, boolean stream, Double temperature, List<String> tools, String toolChoice) {

    /** F9.4: bounds a conversation's length so an unbounded message array can't be used to exhaust memory/CPU per request. */
    public static final int MAX_MESSAGES = 100;

    public ChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("messages must not exceed " + MAX_MESSAGES + " (got " + messages.size() + ")");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream, Double temperature, List<String> tools) {
        this(model, messages, stream, temperature, tools, null);
    }

    public ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream) {
        this(model, messages, stream, null, null, null);
    }

    public ChatCompletionRequest withModel(String upstreamModel) {
        return new ChatCompletionRequest(upstreamModel, messages, stream, temperature, tools, toolChoice);
    }

    public boolean usesTools() {
        return !tools.isEmpty() || messages.stream().anyMatch(ChatMessage::hasToolContent);
    }
}
