package com.aether.gateway.core.domain;

import java.util.Optional;

/** F4.5: bypass cache when temperature > 0.3, tools are present, or X-Aether-No-Cache is set. */
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
        if (!request.tools().isEmpty()) {
            return Optional.of("tools_present");
        }
        return Optional.empty();
    }
}
