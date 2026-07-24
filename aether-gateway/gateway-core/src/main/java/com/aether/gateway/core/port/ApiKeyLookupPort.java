package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ApiKeyContext;

import java.util.Optional;

/** F8.1 (read-only slice needed by M3): looks up quota limits for a presented API key. */
public interface ApiKeyLookupPort {
    Optional<ApiKeyContext> findByRawKey(String rawKey);
}
