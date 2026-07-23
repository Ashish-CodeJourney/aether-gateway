-- Request log: partitioned monthly, since it grows fast under load testing.
-- One partition is created for the current month so the table is usable
-- immediately; the retention/partition-rollover job is added in Phase 08.
CREATE TABLE request_log (
    id              UUID NOT NULL,
    api_key_id      UUID NOT NULL REFERENCES api_key(id),
    trace_id        TEXT,
    route_alias     TEXT,
    provider        TEXT,
    model           TEXT,
    prompt_id       UUID,
    prompt_version  INT,
    streamed        BOOLEAN NOT NULL,
    cache_outcome   TEXT NOT NULL,            -- MISS | EXACT_HIT | SEMANTIC_HIT | BYPASS
    similarity      REAL,
    input_tokens    INT,
    output_tokens   INT,
    cost_usd        NUMERIC(12,6),
    saved_usd       NUMERIC(12,6),
    ttfb_ms         INT,                      -- time to first byte
    total_ms        INT,
    attempt_count   SMALLINT,
    failover_chain  TEXT[],
    status          TEXT NOT NULL,            -- OK | CANCELLED | FAILED | QUOTA_EXCEEDED
    error_code      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX ON request_log (api_key_id, created_at DESC);
CREATE INDEX ON request_log (provider, model, created_at DESC);

-- First partition: current month at migration time. Later phases add a
-- scheduled job to create future partitions; until then, inserts outside
-- this range will fail loudly rather than silently going to a default
-- partition, which is deliberate (PRD makes no mention of a DEFAULT
-- partition and monthly partitioning is meant to be explicit).
CREATE TABLE request_log_2026_07 PARTITION OF request_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE request_log_2026_08 PARTITION OF request_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
