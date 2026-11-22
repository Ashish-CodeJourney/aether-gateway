package com.aether.gateway.core.domain;

import java.util.List;

/**
 * Result of an embeddings call, modelled like {@link ProviderResponse}:
 * failure is data, never an exception. Failures reuse
 * {@link ProviderResponse.ProviderError} so error codes, retryability and
 * failover attribution mean exactly what they mean on the chat path.
 */
public sealed interface EmbeddingOutcome permits EmbeddingOutcome.Success, EmbeddingOutcome.Failure {

    /** Error code for a provider with no embeddings API; the router treats it as "try the next chain member", not as a provider fault. */
    String EMBEDDINGS_NOT_SUPPORTED = "embeddings_not_supported";

    static Failure unsupportedBy(String providerName) {
        return new Failure(new ProviderResponse.ProviderError(
                EMBEDDINGS_NOT_SUPPORTED, "Provider " + providerName + " does not support embeddings", 400, false));
    }

    /**
     * {@code servedByProvider}/{@code servedModel}/{@code attemptCount}/{@code failoverChain}
     * are only known once the router has walked the chain; the 1-arg
     * constructor is what adapters use.
     */
    record Success(EmbeddingResponse response, String servedByProvider, String servedModel, int attemptCount, List<String> failoverChain)
            implements EmbeddingOutcome {
        public Success {
            failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
        }

        public Success(EmbeddingResponse response) {
            this(response, null, null, 1, List.of());
        }
    }

    record Failure(ProviderResponse.ProviderError error) implements EmbeddingOutcome {
        public boolean isUnsupported() {
            return EMBEDDINGS_NOT_SUPPORTED.equals(error.errorCode());
        }
    }
}
