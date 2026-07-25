package com.aether.gateway.providers.gemini.wire;

import java.util.List;

public record WireGeminiContent(String role, List<WireGeminiPart> parts) {
}
