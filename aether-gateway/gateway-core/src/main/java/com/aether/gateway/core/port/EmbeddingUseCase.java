package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;

/** Driving port for {@code POST /v1/embeddings}; gateway-router implements it with the same routing and resilience policy as chat. */
public interface EmbeddingUseCase {
    EmbeddingOutcome embed(EmbeddingRequest request);
}
