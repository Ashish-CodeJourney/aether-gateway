package com.aether.gateway.providers;

import com.aether.gateway.core.domain.ProviderStreamException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Translates a streaming WebClient failure into the domain's
 * {@link ProviderStreamException}, with the same status and
 * retryability rules the non-streaming adapters apply to a
 * {@code ProviderError}.
 */
public final class StreamFailures {

    private StreamFailures() {
    }

    public static Throwable translate(Throwable failure) {
        return switch (failure) {
            case ProviderStreamException alreadyTranslated -> alreadyTranslated;
            case WebClientResponseException response -> {
                int status = response.getStatusCode().value();
                yield new ProviderStreamException(status, "provider_error", response.getMessage(), status == 429 || status >= 500);
            }
            case WebClientRequestException request ->
                    new ProviderStreamException(502, "provider_unreachable", request.getMessage(), true);
            default -> failure;
        };
    }
}
