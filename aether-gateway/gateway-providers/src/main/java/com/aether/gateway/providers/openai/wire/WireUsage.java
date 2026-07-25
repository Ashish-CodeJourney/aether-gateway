package com.aether.gateway.providers.openai.wire;

public record WireUsage(int promptTokens, int completionTokens, int totalTokens) {
}
