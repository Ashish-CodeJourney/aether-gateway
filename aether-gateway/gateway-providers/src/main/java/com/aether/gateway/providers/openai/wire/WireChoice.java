package com.aether.gateway.providers.openai.wire;

public record WireChoice(int index, WireChatMessage message, String finishReason) {
}
