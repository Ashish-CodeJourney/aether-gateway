package com.aether.gateway.core.domain;

import java.util.List;

/**
 * {@code temperature} and {@code tools} exist on this record purely for
 * the cache subsystem (F4.1's cache key, F4.5's bypass rule): this
 * project does not implement tool-calling execution itself, so
 * {@code tools} is carried only as an opaque presence/shape signal, not
 * modelled further.
 */
public record ChatCompletionRequest(
        String model, List<ChatMessage> messages, boolean stream, Double temperature, List<String> tools) {

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

    public ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream) {
        this(model, messages, stream, null, null);
    }
}
