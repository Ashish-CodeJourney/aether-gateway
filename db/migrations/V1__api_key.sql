-- API keys: tenant identity, rate/budget configuration.
-- Keys are stored as salted hashes only (F9.1); key_prefix is display-only.
CREATE TABLE api_key (
    id                    UUID PRIMARY KEY,
    name                  TEXT NOT NULL,
    key_hash              TEXT NOT NULL UNIQUE,
    key_prefix            TEXT NOT NULL,
    tags                  TEXT[] DEFAULT '{}',
    rps_limit             INT,
    concurrency_limit     INT,
    monthly_token_budget  BIGINT,
    monthly_usd_budget    NUMERIC(12,4),
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
