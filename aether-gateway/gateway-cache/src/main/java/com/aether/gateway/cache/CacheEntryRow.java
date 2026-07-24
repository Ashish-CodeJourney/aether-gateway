package com.aether.gateway.cache;

import java.time.Instant;

/** A row from {@code cache_entry}, as much as the cache adapter needs (db/migrations/V3__cache_entry.sql). */
public record CacheEntryRow(
        String id,
        String canonicalPrompt,
        String responseBodyJson,
        String entityFingerprint,
        double similarity,
        Instant expiresAt) {
}
