package com.aether.gateway.core.domain;

import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<ChatCompletionChoice> choices,
        Usage usage) {
    public ChatCompletionResponse {
        choices = List.copyOf(choices);
    }
}
