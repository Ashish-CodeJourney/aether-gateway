package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.ProviderResponse;

/**
 * Internal-only bridge: {@link ResilientRouter} throws this from inside
 * a Resilience4j-decorated supplier so the circuit breaker's exception-
 * based failure counting actually sees a retryable
 * {@link ProviderResponse.ProviderError}, which {@code ProviderAdapter}
 * otherwise returns as a plain value, not a thrown exception. Always
 * caught and unwrapped back to the value immediately after the
 * decorated call; never escapes {@link ResilientRouter}.
 */
class ProviderCallFailedException extends RuntimeException {
    final ProviderResponse.ProviderError error;

    ProviderCallFailedException(ProviderResponse.ProviderError error) {
        super(error.message(), null, false, false);
        this.error = error;
    }
}
