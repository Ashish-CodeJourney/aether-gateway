package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;

import java.util.concurrent.Flow;

/**
 * Temporary, M1-only streaming seam, the streaming counterpart to
 * {@link ProviderCaller}. Replaced by the streaming half of the real
 * ProviderAdapter port in Phase 05, same as ProviderCaller.
 */
public interface StreamingProviderCaller {
    Flow.Publisher<ProviderResponse.StreamChunk> callStreaming(ChatCompletionRequest request);
}
