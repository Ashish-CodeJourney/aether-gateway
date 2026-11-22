package com.aether.gateway.core.domain;

/**
 * Thrown on the streaming path when the provider chosen for a request
 * cannot carry tool calling: a stream has no error value to return, and
 * silently dropping the tools would hand the client a plausible but wrong
 * answer. Raised before any bytes are sent so it can still surface as a
 * plain 400. The non-streaming path reports the same condition as a
 * {@code ProviderError} with {@link #ERROR_CODE}.
 */
public class ToolsNotSupportedException extends IllegalArgumentException {

    public static final String ERROR_CODE = "tools_not_supported";

    public ToolsNotSupportedException(String providerName) {
        super(messageFor(providerName));
    }

    public static String messageFor(String providerName) {
        return "Provider '" + providerName + "' does not support tool calling; "
                + "route this model to an OpenAI-compatible or Anthropic provider, or remove tools and tool messages from the request";
    }

    public static ProviderResponse.ProviderError asProviderError(String providerName) {
        return new ProviderResponse.ProviderError(ERROR_CODE, messageFor(providerName), 400, false);
    }
}
