package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;

import java.util.concurrent.Flow;

/**
 * F2.1: normalises a request, invokes a specific named provider, and
 * normalises the response/stream back to domain types. One instance
 * exists per configured provider (mock, later ollama/groq/gemini/openai-
 * compatible), all implemented in gateway-providers. gateway-router
 * depends only on this port, never on a concrete adapter, so swapping or
 * adding a provider requires zero gateway-router changes (the literal
 * proof point for Phase 12, per HEXAGONAL-ARCHITECTURE-GUIDE.md section
 * 4, item 3).
 */
public interface ProviderAdapter {

    /** The name this adapter is registered under in routing.yaml (e.g. "mock-primary"). */
    String providerName();

    ProviderResponse invoke(ChatCompletionRequest request);

    Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request);
}
