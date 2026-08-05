package com.aether.gateway.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Keeps a rolling window of future monthly {@code request_log}
 * partitions open.
 *
 * <p>{@code request_log} is RANGE-partitioned by {@code created_at} with
 * no DEFAULT partition (V2's deliberate choice: fail loudly rather than
 * silently pool rows). That choice is only safe while some component
 * actually creates the next month's partition ahead of time - without
 * one, the table stops accepting writes on a fixed calendar date.
 *
 * <p>Creation itself lives in the {@code ensure_request_log_partition}
 * SQL function (V6), which takes a transaction-scoped advisory lock, so
 * every replica can run this concurrently and exactly one of them wins
 * each month.
 */
public class RequestLogPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(RequestLogPartitionMaintainer.class);

    private final JdbcClient jdbcClient;
    private final int monthsAhead;

    /**
     * @param monthsAhead how many months past the current one to keep
     *                    open. The window must comfortably exceed the
     *                    maintenance interval so a few missed runs (a
     *                    replica down, a scheduler paused) still cannot
     *                    reach the edge.
     */
    public RequestLogPartitionMaintainer(JdbcClient jdbcClient, int monthsAhead) {
        if (monthsAhead < 1) {
            throw new IllegalArgumentException("monthsAhead must be at least 1, was " + monthsAhead);
        }
        this.jdbcClient = jdbcClient;
        this.monthsAhead = monthsAhead;
    }

    /**
     * Ensures a partition exists for the current month and each of the
     * next {@code monthsAhead}.
     *
     * @return how many partitions this call actually created
     */
    public int ensurePartitions() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int created = 0;
        for (int offset = 0; offset <= monthsAhead; offset++) {
            if (ensurePartitionForMonthOf(today.plusMonths(offset))) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Created {} request_log partition(s); window now covers {} month(s) ahead", created, monthsAhead);
        }
        return created;
    }

    /**
     * Ensures a partition exists for the month containing {@code day}.
     * Exposed separately so historic months can be backfilled - restoring
     * an old export, or recovering rows buffered while the table was
     * refusing writes.
     *
     * @return true when this call created the partition
     */
    public boolean ensurePartitionForMonthOf(LocalDate day) {
        return Boolean.TRUE.equals(jdbcClient.sql("SELECT ensure_request_log_partition(:month)")
                .param("month", java.sql.Date.valueOf(day))
                .query(Boolean.class)
                .single());
    }
}
