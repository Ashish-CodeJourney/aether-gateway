package com.aether.gateway.providers.openai.wire;

import java.util.List;

public record WireChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<WireChoice> choices,
        WireUsage usage) {
}
