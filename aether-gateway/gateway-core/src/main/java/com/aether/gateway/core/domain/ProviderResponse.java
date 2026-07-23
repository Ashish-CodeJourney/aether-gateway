package com.aether.gateway.core.domain;

/**
 * PRD section 10: domain model for provider results, expressed as a
 * sealed interface so every call site is forced to handle all three
 * outcomes (pattern matching gives a compile error on a missing case).
 */
public sealed interface ProviderResponse permits
        ProviderResponse.Completion,
        ProviderResponse.StreamChunk,
        ProviderResponse.ProviderError {

    record Completion(ChatCompletionResponse response) implements ProviderResponse {
    }

    record StreamChunk(String id, int index, String deltaContent, boolean last) implements ProviderResponse {
    }

    record ProviderError(String errorCode, String message, int httpStatus, boolean retryable) implements ProviderResponse {
    }
}
