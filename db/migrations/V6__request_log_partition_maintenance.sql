-- V2 shipped request_log with exactly two hand-written monthly
-- partitions and left the rollover job to "Phase 08", which never
-- landed. Because V2 deliberately declined a DEFAULT partition, every
-- insert past the last partition does not degrade - it fails outright,
-- turning a missing maintenance job into a dated, total outage of the
-- request log (and of everything built on it: usage queries, cost
-- attribution, billing data).
--
-- This function is the rollover mechanism. It is called at startup and
-- on a daily schedule by RequestLogPartitionMaintainer, which keeps a
-- rolling window of future months open so the table is never within a
-- month of the same failure again.
--
-- Concurrency: several gateway replicas run the maintainer independently
-- and will race on the same month. A transaction-scoped advisory lock
-- serialises them, so exactly one replica creates each partition and the
-- rest observe it already present rather than erroring on a duplicate.
CREATE OR REPLACE FUNCTION ensure_request_log_partition(p_month DATE)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_start          DATE := date_trunc('month', p_month)::DATE;
    v_end            DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::DATE;
    v_partition_name TEXT := format('request_log_%s', to_char(v_start, 'YYYY_MM'));
BEGIN
    -- Hash the partition name into the advisory lock key so replicas
    -- only serialise against the one month they are both creating, not
    -- against all partition maintenance globally.
    PERFORM pg_advisory_xact_lock(hashtext('aether.request_log_partition.' || v_partition_name));

    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = v_partition_name) THEN
        RETURN FALSE;
    END IF;

    EXECUTE format(
        'CREATE TABLE %I PARTITION OF request_log FOR VALUES FROM (%L) TO (%L)',
        v_partition_name, v_start, v_end);

    RETURN TRUE;
END;
$$;

COMMENT ON FUNCTION ensure_request_log_partition(DATE) IS
    'Creates the monthly request_log partition containing p_month if absent. '
    'Returns TRUE when it created one. Idempotent and safe to run concurrently.';

-- Close the immediate gap at migration time: the two partitions V2
-- created run out on 2026-09-01, so open the current month and the next
-- twelve straight away. From here the maintainer keeps the window
-- rolling; this call only means a database is never left exposed
-- between running migrations and the first scheduled maintenance pass.
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 0..12 LOOP
        PERFORM ensure_request_log_partition((CURRENT_DATE + (i || ' months')::INTERVAL)::DATE);
    END LOOP;
END;
$$;
