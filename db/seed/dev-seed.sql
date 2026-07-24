-- Dev/test-only seed data, per Phase 06 (M3) task 9: "seed at least one
-- test API key with a known rps_limit and monthly budget." NOT run by
-- Flyway (Flyway migrations are schema, not seed data); run this
-- manually against a dev database, e.g.:
--   psql "$GATEWAY_DB_URL" -f db/seed/dev-seed.sql
--
-- Raw key: aeth_test_key_m3
-- key_hash below is SHA-256(raw key), matching ApiKeyHasher.hash() in
-- gateway-quota (F9.1: keys are stored as salted hashes, never
-- plaintext; verified with `printf '%s' aeth_test_key_m3 | sha256sum`).
INSERT INTO api_key (
    id, name, key_hash, key_prefix, tags,
    rps_limit, concurrency_limit, monthly_token_budget, monthly_usd_budget,
    enabled
) VALUES (
    '0c053471-c3be-45a1-bf0a-9e68b5924114',
    'M3 dev/test key',
    '0dfdc9190a9660b70af4154ef928b8fd5b3078a5c60924bb6b4642dec029135c',
    'aeth_test',
    ARRAY['dev', 'test'],
    100,
    50,
    100000,
    50.0000,
    TRUE
)
ON CONFLICT (id) DO NOTHING;
