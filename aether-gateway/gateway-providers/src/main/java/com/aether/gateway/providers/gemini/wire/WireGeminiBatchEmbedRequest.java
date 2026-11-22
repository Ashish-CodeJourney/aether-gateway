package com.aether.gateway.providers.gemini.wire;

import java.util.List;

public record WireGeminiBatchEmbedRequest(List<WireGeminiEmbedContentRequest> requests) {
}
