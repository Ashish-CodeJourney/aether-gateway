package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiRequest(
        List<WireGeminiContent> contents,
        WireGeminiSystemInstruction systemInstruction,
        List<WireGeminiTool> tools,
        WireGeminiToolConfig toolConfig) {
}
