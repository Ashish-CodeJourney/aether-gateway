package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;

/**
 * Driving port: called by gateway-proxy (and, once wired, gateway-admin's
 * test endpoints) to execute a chat completion. gateway-router implements
 * this by orchestrating driven ports.
 */
public interface ChatCompletionUseCase {
    ProviderResponse complete(ChatCompletionRequest request);
}
