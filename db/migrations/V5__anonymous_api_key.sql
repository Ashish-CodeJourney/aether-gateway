-- Phase 08 (M5): request_log.api_key_id is NOT NULL (V2__request_log.sql),
-- but M3/M4 both treat a request with no Authorization header as a
-- legitimate, unmetered/uncached-under-a-shared-namespace case rather
-- than a rejected one. This row is the FK target for logging those
-- requests: a well-known, fixed-id, disabled system placeholder, never
-- intended to be presented as a real credential. key_hash below is the
-- SHA-256 hex digest of the literal string
-- 'aether-system-anonymous-placeholder-never-a-real-key' (computed with
-- `sha256sum`, not a Postgres crypto function, to avoid depending on
-- pgcrypto or a specific Postgres version's built-in hash support -
-- same approach as db/seed/dev-seed.sql).
INSERT INTO api_key (
    id, name, key_hash, key_prefix, tags,
    rps_limit, concurrency_limit, monthly_token_budget, monthly_usd_budget,
    enabled
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    'System: anonymous/unauthenticated requests',
    '2ff3241137bd8c300cc629eab3464c50da462c4b435fd8380731206e89aae7a7',
    'system',
    ARRAY['system', 'anonymous'],
    NULL,
    NULL,
    NULL,
    NULL,
    FALSE
)
ON CONFLICT (id) DO NOTHING;
