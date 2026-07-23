package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ChatStreamUseCase;

import java.util.concurrent.Flow;

public class ChatStreamOrchestrator implements ChatStreamUseCase {

    private final StreamingProviderCaller providerCaller;

    public ChatStreamOrchestrator(StreamingProviderCaller providerCaller) {
        this.providerCaller = providerCaller;
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> stream(ChatCompletionRequest request) {
        return providerCaller.callStreaming(request);
    }
}
