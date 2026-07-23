package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;

import java.util.concurrent.Flow;

/**
 * Driving port for {@code stream: true} requests. Returns
 * {@link java.util.concurrent.Flow.Publisher}, the JDK's own
 * reactive-streams interface (java.util.concurrent, since Java 9), not
 * Reactor's {@code Flux}, because gateway-core carries zero framework
 * dependencies (ADR-001). gateway-router's implementation adapts a
 * Reactor-based HTTP call to this JDK type via
 * {@code JdkFlowAdapter.publisherToFlowPublisher}; gateway-proxy adapts
 * it back to {@code Flux} via {@code JdkFlowAdapter.flowPublisherToFlux}
 * for its WebFlux controller. Cancellation propagates correctly through
 * both adaptations because both directions implement the same
 * reactive-streams cancellation contract Flow.Publisher and Reactor's
 * Publisher share.
 */
public interface ChatStreamUseCase {
    Flow.Publisher<ProviderResponse.StreamChunk> stream(ChatCompletionRequest request);
}
