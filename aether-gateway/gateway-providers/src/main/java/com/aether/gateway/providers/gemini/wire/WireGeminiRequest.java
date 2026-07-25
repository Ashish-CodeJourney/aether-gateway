package com.aether.gateway.providers.gemini.wire;

import java.util.List;

public record WireGeminiRequest(List<WireGeminiContent> contents, WireGeminiSystemInstruction systemInstruction) {
}
