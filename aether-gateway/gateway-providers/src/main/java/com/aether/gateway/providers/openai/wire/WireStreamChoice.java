package com.aether.gateway.providers.openai.wire;

public record WireStreamChoice(int index, WireDelta delta, String finishReason) {
}
