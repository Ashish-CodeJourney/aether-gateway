package com.aether.gateway.providers.openai.wire;

import java.util.List;

/**
 * {@code usage} is only present on the extra chunk a server sends when
 * asked with {@code stream_options.include_usage}; that chunk's
 * {@code choices} is empty.
 */
public record WireStreamChunkResponse(
        String id,
        String object,
        long created,
        String model,
        List<WireStreamChoice> choices,
        WireUsage usage) {
}
