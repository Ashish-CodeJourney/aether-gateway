package com.aether.gateway.observability;

import com.aether.gateway.core.domain.UsageAggregate;
import com.aether.gateway.core.port.UsageQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** F8.4: aggregates request_log by a whitelisted grouping column - never raw user SQL. */
public class JdbcUsageQueryRepository implements UsageQueryPort {

    private static final Map<String, String> GROUP_BY_COLUMNS = Map.of(
            "key", "api_key_id",
            "provider", "provider",
            "model", "model",
            "route", "route_alias");

    private final JdbcClient jdbcClient;

    public JdbcUsageQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<UsageAggregate> query(Instant from, Instant to, String groupBy) {
        String column = GROUP_BY_COLUMNS.get(groupBy);
        if (column == null) {
            throw new IllegalArgumentException("Unknown groupBy value: " + groupBy + " (expected one of " + GROUP_BY_COLUMNS.keySet() + ")");
        }
        return jdbcClient.sql("""
                        SELECT %s AS group_value,
                               COUNT(*) AS request_count,
                               COALESCE(SUM(input_tokens), 0) AS input_tokens,
                               COALESCE(SUM(output_tokens), 0) AS output_tokens,
                               COALESCE(SUM(cost_usd), 0) AS cost_usd,
                               COALESCE(SUM(saved_usd), 0) AS saved_usd
                        FROM request_log
                        WHERE created_at >= :from AND created_at < :to
                        GROUP BY %s
                        ORDER BY cost_usd DESC
                        """.formatted(column, column))
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new UsageAggregate(
                        String.valueOf(rs.getObject("group_value")),
                        rs.getLong("request_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getBigDecimal("saved_usd")))
                .list();
    }
}
