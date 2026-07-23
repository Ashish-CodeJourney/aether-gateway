package com.aether.gateway.core.domain;

public record ChatCompletionChoice(int index, ChatMessage message, String finishReason) {
}
