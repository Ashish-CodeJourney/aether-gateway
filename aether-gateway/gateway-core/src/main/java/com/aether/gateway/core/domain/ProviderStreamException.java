package com.aether.gateway.core.domain;

/**
 * A streaming provider call that failed at the provider: rejected
 * (4xx), errored (5xx) or unreachable. Streaming adapters signal it as
 * the stream's error so the proxy can answer with the provider's real
 * status, the same as a non-streaming {@link ProviderResponse.ProviderError}.
 */
public final class ProviderStreamException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;
    private final boolean retryable;

    public ProviderStreamException(int httpStatus, String errorCode, String message, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
