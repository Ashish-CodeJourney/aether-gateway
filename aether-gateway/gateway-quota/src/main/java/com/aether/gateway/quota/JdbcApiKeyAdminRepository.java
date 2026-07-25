package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F8.1: full API key CRUD (create/list/update/delete), separate from {@link JdbcApiKeyRepository}'s read-only quota-lookup slice. */
public class JdbcApiKeyAdminRepository implements ApiKeyAdminPort {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;

    public JdbcApiKeyAdminRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public CreatedApiKey create(String name, List<String> tags, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        String rawKey = generateRawKey();
        String hash = ApiKeyHasher.hash(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(12, rawKey.length()));
        UUID id = UUID.randomUUID();

        jdbcClient.sql("""
                        INSERT INTO api_key (id, name, key_hash, key_prefix, tags, rps_limit, concurrency_limit, monthly_token_budget)
                        VALUES (:id, :name, :hash, :prefix, :tags, :rps, :concurrency, :budget)
                        """)
                .param("id", id)
                .param("name", name)
                .param("hash", hash)
                .param("prefix", keyPrefix)
                .param("tags", tags.toArray(new String[0]))
                .param("rps", rpsLimit)
                .param("concurrency", concurrencyLimit)
                .param("budget", monthlyTokenBudget)
                .update();

        ApiKeySummary summary = findById(id.toString())
                .orElseThrow(() -> new IllegalStateException("Just-inserted api_key not found: " + id));
        return new CreatedApiKey(summary, rawKey);
    }

    @Override
    public List<ApiKeySummary> list() {
        return jdbcClient.sql("""
                        SELECT id, name, key_prefix, tags, rps_limit, concurrency_limit,
                               monthly_token_budget, monthly_usd_budget, enabled, created_at
                        FROM api_key
                        ORDER BY created_at DESC
                        """)
                .query(this::toSummary)
                .list();
    }

    @Override
    public Optional<ApiKeySummary> update(String id, Boolean enabled, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        int updated = jdbcClient.sql("""
                        UPDATE api_key SET
                            enabled = COALESCE(:enabled, enabled),
                            rps_limit = COALESCE(:rps, rps_limit),
                            concurrency_limit = COALESCE(:concurrency, concurrency_limit),
                            monthly_token_budget = COALESCE(:budget, monthly_token_budget)
                        WHERE id = :id
                        """)
                .param("enabled", enabled)
                .param("rps", rpsLimit)
                .param("concurrency", concurrencyLimit)
                .param("budget", monthlyTokenBudget)
                .param("id", UUID.fromString(id))
                .update();
        return updated == 0 ? Optional.empty() : findById(id);
    }

    @Override
    public boolean delete(String id) {
        int deleted = jdbcClient.sql("DELETE FROM api_key WHERE id = :id")
                .param("id", UUID.fromString(id))
                .update();
        return deleted > 0;
    }

    private Optional<ApiKeySummary> findById(String id) {
        return jdbcClient.sql("""
                        SELECT id, name, key_prefix, tags, rps_limit, concurrency_limit,
                               monthly_token_budget, monthly_usd_budget, enabled, created_at
                        FROM api_key
                        WHERE id = :id
                        """)
                .param("id", UUID.fromString(id))
                .query(this::toSummary)
                .optional();
    }

    private ApiKeySummary toSummary(ResultSet rs, int rowNum) throws SQLException {
        String[] tags = (String[]) rs.getArray("tags").getArray();
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        return new ApiKeySummary(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("key_prefix"),
                List.of(tags),
                (Integer) rs.getObject("rps_limit"),
                (Integer) rs.getObject("concurrency_limit"),
                (Long) rs.getObject("monthly_token_budget"),
                rs.getBigDecimal("monthly_usd_budget"),
                rs.getBoolean("enabled"),
                createdAt);
    }

    private static String generateRawKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "aeth_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
