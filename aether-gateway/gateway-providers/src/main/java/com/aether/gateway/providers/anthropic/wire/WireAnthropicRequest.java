package com.aether.gateway.providers.anthropic.wire;

import java.util.List;

/**
 * {@code maxTokens} serialises to the required {@code max_tokens} field.
 * {@code system} is null when the caller sent no system message; the
 * adapter's mapper omits nulls rather than sending {@code "system": null},
 * which the API rejects.
 */
public record WireAnthropicRequest(
        String model,
        List<WireAnthropicMessage> messages,
        Integer maxTokens,
        String system,
        boolean stream) {
}
