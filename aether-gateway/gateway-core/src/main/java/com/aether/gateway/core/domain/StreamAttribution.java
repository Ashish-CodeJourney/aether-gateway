package com.aether.gateway.core.domain;

import java.util.List;

/**
 * Which provider a stream ended up on and how it got there: the same
 * {@code servedByProvider}/{@code attemptCount}/{@code failoverChain}
 * a non-streaming {@link ProviderResponse.Completion} carries.
 * {@code servedByProvider} is null when no provider served the stream.
 */
public record StreamAttribution(String servedByProvider, int attemptCount, List<String> failoverChain) {
    public StreamAttribution {
        failoverChain = failoverChain == null ? List.of() : List.copyOf(failoverChain);
    }
}
