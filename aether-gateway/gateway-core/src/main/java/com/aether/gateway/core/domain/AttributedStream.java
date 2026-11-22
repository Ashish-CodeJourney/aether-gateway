package com.aether.gateway.core.domain;

import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * A routed stream that can say which provider served it. Routing
 * settles before the stream's first signal (a chunk, completion, or a
 * failure before any chunk) is delivered, so the attribution is present
 * from that point on and empty before it.
 */
public interface AttributedStream extends Flow.Publisher<ProviderResponse.StreamChunk> {
    Optional<StreamAttribution> attribution();
}
