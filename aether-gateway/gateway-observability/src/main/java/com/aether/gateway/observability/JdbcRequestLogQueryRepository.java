package com.aether.gateway.observability;

import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.port.RequestLogQueryPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Read-only counterpart to {@link JdbcRequestLogWriter}; the console's request explorer screen. */
public class JdbcRequestLogQueryRepository implements RequestLogQueryPort {

    private final JdbcClient jdbcClient;

    public JdbcRequestLogQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<RequestLogEntry> recent(int limit) {
        return jdbcClient.sql("""
                        SELECT id, api_key_id, trace_id, route_alias, provider, model,
                               prompt_id, prompt_version, streamed, cache_outcome, similarity,
                               input_tokens, output_tokens, cost_usd, saved_usd,
                               ttfb_ms, total_ms, attempt_count, failover_chain,
                               status, error_code, created_at
                        FROM request_log
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(this::toEntry)
                .list();
    }

    private RequestLogEntry toEntry(ResultSet rs, int rowNum) throws SQLException {
        String[] failoverChain = rs.getArray("failover_chain") != null
                ? (String[]) rs.getArray("failover_chain").getArray()
                : new String[0];
        String promptIdStr = rs.getString("prompt_id");
        return new RequestLogEntry(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("api_key_id")),
                rs.getString("trace_id"),
                rs.getString("route_alias"),
                rs.getString("provider"),
                rs.getString("model"),
                promptIdStr != null ? UUID.fromString(promptIdStr) : null,
                (Integer) rs.getObject("prompt_version"),
                rs.getBoolean("streamed"),
                rs.getString("cache_outcome"),
                similarityOf(rs),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                rs.getBigDecimal("cost_usd"),
                rs.getBigDecimal("saved_usd"),
                (Integer) rs.getObject("ttfb_ms"),
                (Integer) rs.getObject("total_ms"),
                (Integer) rs.getObject("attempt_count"),
                List.of(failoverChain),
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * {@code similarity} is a Postgres {@code real} (float4) column;
     * this driver version's {@code getObject(col, Double.class)}
     * rejects that conversion outright ("conversion to class
     * java.lang.Double from float4 not supported"), unlike the
     * documented JDBC 4.1 behaviour - {@code getFloat} + {@code wasNull}
     * is the reliable null-safe read for this column type.
     */
    private static Double similarityOf(ResultSet rs) throws SQLException {
        float value = rs.getFloat("similarity");
        return rs.wasNull() ? null : (double) value;
    }
}
