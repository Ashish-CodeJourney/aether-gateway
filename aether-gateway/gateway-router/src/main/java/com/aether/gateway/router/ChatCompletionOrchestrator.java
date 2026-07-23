package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ChatCompletionUseCase;

/**
 * M0 implementation of the driving port: forwards every request to a
 * single hardcoded provider caller (the mock provider). Routing policy,
 * failover, and resilience are added in Phase 05 without changing this
 * class's public shape (the driving port stays the same; only what sits
 * behind {@link ProviderCaller} grows more capable).
 */
public class ChatCompletionOrchestrator implements ChatCompletionUseCase {

    private final ProviderCaller providerCaller;

    public ChatCompletionOrchestrator(ProviderCaller providerCaller) {
        this.providerCaller = providerCaller;
    }

    @Override
    public ProviderResponse complete(ChatCompletionRequest request) {
        return providerCaller.call(request);
    }
}
