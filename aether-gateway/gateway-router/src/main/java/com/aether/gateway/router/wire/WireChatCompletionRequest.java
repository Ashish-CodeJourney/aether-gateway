package com.aether.gateway.router.wire;

import java.util.List;

public record WireChatCompletionRequest(String model, List<WireChatMessage> messages, boolean stream) {
}
