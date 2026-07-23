package com.aether.gateway.providers.mock.wire;

import java.util.List;

public record WireStreamChunkResponse(
        String id,
        String object,
        long created,
        String model,
        List<WireStreamChoice> choices) {
}
