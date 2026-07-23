-- Semantic cache storage. pgvector must be enabled before this migration
-- runs; CREATE EXTENSION is idempotent so this is safe to run every time.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE cache_entry (
    id                  UUID PRIMARY KEY,
    namespace           TEXT NOT NULL,            -- isolation boundary (api_key_id or tenant)
    exact_hash          TEXT NOT NULL,
    embedding           vector(384) NOT NULL,
    canonical_prompt    TEXT NOT NULL,
    response_body       JSONB NOT NULL,
    model               TEXT NOT NULL,
    input_tokens        INT,
    output_tokens       INT,
    original_cost_usd   NUMERIC(12,6),
    entity_fingerprint  TEXT,                      -- numbers/dates/entities, for the guard rule
    hit_count           INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ON cache_entry (namespace, exact_hash);
CREATE INDEX ON cache_entry USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX ON cache_entry (expires_at);
