package com.aether.gateway.core.domain;

import java.util.List;

public record ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream) {
    public ChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
    }
}
