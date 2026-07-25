package com.aether.gateway.providers.gemini.wire;

public record WireGeminiCandidate(WireGeminiContent content, String finishReason, int index) {
}
