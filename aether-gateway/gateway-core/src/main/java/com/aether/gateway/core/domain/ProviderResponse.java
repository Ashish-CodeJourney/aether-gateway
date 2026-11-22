package com.aether.gateway.core.domain;

import java.util.List;

/**
 * PRD section 10: domain model for provider results, expressed as a
 * sealed interface so every call site is forced to handle all three
 * outcomes (pattern matching gives a compile error on a missing case).
 */
public sealed interface ProviderResponse permits
        ProviderResponse.Completion,
        ProviderResponse.StreamChunk,
        ProviderResponse.ProviderError {

    /**
     * {@code servedByProvider}/{@code attemptCount}/{@code failoverChain}
     * (F6.1's provider tag, request_log's attempt_count/failover_chain
     * columns) are only meaningful once {@code ResilientRouter} has
     * finished walking the chain; a bare adapter has no notion of
     * retries or failover, so the 1-arg constructor - used by adapters
     * and by every pre-existing test - defaults them to "single successful
     * attempt, no failover, provider unknown at this layer."
     */
    record Completion(ChatCompletionResponse response, String servedByProvider, int attemptCount, List<String> failoverChain)
            implements ProviderResponse {
        public Completion {
            failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
        }

        public Completion(ChatCompletionResponse response) {
            this(response, null, 1, List.of());
        }
    }

    /**
     * {@code finishReason} is the provider's own reason when it gave one
     * (e.g. "tool_calls"); null leaves the choice to the caller, which
     * reports "stop" for a last chunk.
     */
    record StreamChunk(String id, int index, String deltaContent, boolean last, List<ToolCallDelta> toolCallDeltas, String finishReason)
            implements ProviderResponse {
        public StreamChunk {
            toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
        }

        public StreamChunk(String id, int index, String deltaContent, boolean last) {
            this(id, index, deltaContent, last, List.of(), null);
        }
    }

    /** {@code attemptCount}/{@code failoverChain}: see {@link Completion}'s javadoc; same reasoning applies here. */
    record ProviderError(String errorCode, String message, int httpStatus, boolean retryable, int attemptCount, List<String> failoverChain)
            implements ProviderResponse {
        public ProviderError {
            failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
        }

        public ProviderError(String errorCode, String message, int httpStatus, boolean retryable) {
            this(errorCode, message, httpStatus, retryable, 1, List.of());
        }
    }
}
