package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One part of a turn: text, a {@code functionCall} the model made, or a
 * {@code functionResponse} carrying a call's result. {@code thoughtSignature}
 * is Gemini 3's opaque token tying a function call to the model's
 * reasoning; {@code thought} marks a reasoning-summary part, which is not
 * reply text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiPart(
        String text,
        WireGeminiFunctionCall functionCall,
        WireGeminiFunctionResponse functionResponse,
        String thoughtSignature,
        Boolean thought) {

    public WireGeminiPart(String text) {
        this(text, null, null, null, null);
    }

    public static WireGeminiPart functionCall(WireGeminiFunctionCall call, String thoughtSignature) {
        return new WireGeminiPart(null, call, null, thoughtSignature, null);
    }

    public static WireGeminiPart functionResponse(WireGeminiFunctionResponse response) {
        return new WireGeminiPart(null, null, response, null, null);
    }
}
