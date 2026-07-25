package com.aether.gateway.providers.gemini.wire;

import java.util.List;

public record WireGeminiResponse(List<WireGeminiCandidate> candidates, WireGeminiUsageMetadata usageMetadata) {
}
