package com.aether.gateway.core.domain;

import java.util.Optional;

/**
 * F4.5: bypass cache when temperature > 0.3, tools are in play, or X-Aether-No-Cache is set.
 * "Tools in play" covers tool definitions and any assistant tool call or tool result in the
 * conversation: an agent loop's next step depends on tool results the cache knows nothing
 * about, so tool-calling traffic is never cached (see also the controller's store guard).
 */
public final class CacheBypassRule {

    private static final double MAX_CACHEABLE_TEMPERATURE = 0.3;

    private CacheBypassRule() {
    }

    public static Optional<String> bypassReason(ChatCompletionRequest request, boolean noCacheHeaderPresent) {
        if (noCacheHeaderPresent) {
            return Optional.of("no_cache_header");
        }
        if (request.temperature() != null && request.temperature() > MAX_CACHEABLE_TEMPERATURE) {
            return Optional.of("temperature_too_high");
        }
        if (request.usesTools()) {
            return Optional.of("tools_present");
        }
        return Optional.empty();
    }
}
