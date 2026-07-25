package com.aether.gateway.providers.gemini.wire;

public record WireGeminiUsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount, Integer totalTokenCount) {
}
