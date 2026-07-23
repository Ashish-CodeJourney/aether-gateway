package com.aether.gateway.providers.mock.wire;

import java.util.List;

public record WireChatCompletionRequest(String model, List<WireChatMessage> messages, boolean stream) {
}
