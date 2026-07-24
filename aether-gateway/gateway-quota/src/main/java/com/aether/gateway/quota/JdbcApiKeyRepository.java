package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/** F8.1 (read-only slice needed by M3). ADR-007: JdbcClient directly, no Spring Data JPA. */
public class JdbcApiKeyRepository implements ApiKeyLookupPort {

    private final JdbcClient jdbcClient;

    public JdbcApiKeyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ApiKeyContext> findByRawKey(String rawKey) {
        String hash = ApiKeyHasher.hash(rawKey);
        return jdbcClient.sql("""
                        SELECT id, rps_limit, concurrency_limit, monthly_token_budget
                        FROM api_key
                        WHERE key_hash = :hash AND enabled = TRUE
                        """)
                .param("hash", hash)
                .query((rs, rowNum) -> new ApiKeyContext(
                        rs.getString("id"),
                        (Integer) rs.getObject("rps_limit"),
                        (Integer) rs.getObject("concurrency_limit"),
                        (Long) rs.getObject("monthly_token_budget")))
                .optional();
    }
}
