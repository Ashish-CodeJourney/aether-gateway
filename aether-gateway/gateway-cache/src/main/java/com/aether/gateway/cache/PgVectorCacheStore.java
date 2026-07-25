package com.aether.gateway.cache;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F4.2 / F4.4: the durable store behind the Redis exact-match hot cache
 * (F4.1) and the source of truth for semantic search. ADR-007: plain
 * {@link JdbcClient}, not Spring AI's {@code VectorStore} abstraction,
 * because this needs direct control over {@code namespace},
 * {@code entity_fingerprint}, {@code expires_at}, and HNSW query
 * parameters that {@code VectorStore} does not expose.
 */
public class PgVectorCacheStore {

    private final JdbcClient jdbcClient;

    public PgVectorCacheStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CacheEntryRow> findByExactHash(String namespace, String exactHash) {
        return jdbcClient.sql("""
                        SELECT id, canonical_prompt, response_body, entity_fingerprint, expires_at
                        FROM cache_entry
                        WHERE namespace = :namespace AND exact_hash = :exactHash AND expires_at > now()
                        """)
                .param("namespace", namespace)
                .param("exactHash", exactHash)
                .query((rs, rowNum) -> new CacheEntryRow(
                        rs.getString("id"),
                        rs.getString("canonical_prompt"),
                        rs.getString("response_body"),
                        rs.getString("entity_fingerprint"),
                        1.0,
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /**
     * F4.2/F4.4: cosine-nearest candidates within a namespace, ordered
     * most-similar first. pgvector's {@code <=>} operator is cosine
     * *distance* (0 = identical, 2 = opposite); similarity is
     * {@code 1 - distance}. The embedding is passed as a bracketed
     * literal (pgvector's textual input format) rather than requiring
     * the pgvector-java extension, cast to {@code vector} in SQL.
     */
    public List<CacheEntryRow> findNearest(String namespace, float[] embedding, int limit) {
        String vectorLiteral = toVectorLiteral(embedding);
        return jdbcClient.sql("""
                        SELECT id, canonical_prompt, response_body, entity_fingerprint, expires_at,
                               1 - (embedding <=> :embedding::vector) AS similarity
                        FROM cache_entry
                        WHERE namespace = :namespace AND expires_at > now()
                        ORDER BY embedding <=> :embedding::vector
                        LIMIT :limit
                        """)
                .param("namespace", namespace)
                .param("embedding", vectorLiteral)
                .param("limit", limit)
                .query((rs, rowNum) -> new CacheEntryRow(
                        rs.getString("id"),
                        rs.getString("canonical_prompt"),
                        rs.getString("response_body"),
                        rs.getString("entity_fingerprint"),
                        rs.getDouble("similarity"),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    public String insert(
            String namespace,
            String exactHash,
            float[] embedding,
            String canonicalPrompt,
            String responseBodyJson,
            String model,
            String entityFingerprint,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO cache_entry (
                            id, namespace, exact_hash, embedding, canonical_prompt,
                            response_body, model, entity_fingerprint, expires_at
                        ) VALUES (
                            :id, :namespace, :exactHash, :embedding::vector, :canonicalPrompt,
                            :responseBody::jsonb, :model, :entityFingerprint, :expiresAt
                        )
                        ON CONFLICT (namespace, exact_hash) DO UPDATE SET
                            response_body = EXCLUDED.response_body,
                            expires_at = EXCLUDED.expires_at
                        """)
                .param("id", id)
                .param("namespace", namespace)
                .param("exactHash", exactHash)
                .param("embedding", toVectorLiteral(embedding))
                .param("canonicalPrompt", canonicalPrompt)
                .param("responseBody", responseBodyJson)
                .param("model", model)
                .param("entityFingerprint", entityFingerprint)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
        return id.toString();
    }

    /** F8.3: live (non-expired) entry count for a namespace, for {@code GET /admin/cache/stats}. */
    public long countByNamespace(String namespace) {
        return jdbcClient.sql("SELECT COUNT(*) FROM cache_entry WHERE namespace = :namespace AND expires_at > now()")
                .param("namespace", namespace)
                .query(Long.class)
                .single();
    }

    /** F4.7: manual invalidation. {@code modelPrefix} matches the start of the stored {@code model} column. */
    public long deleteByNamespaceAndModelPrefix(String namespace, String modelPrefix) {
        return jdbcClient.sql("""
                        DELETE FROM cache_entry
                        WHERE namespace = :namespace AND model LIKE :modelPrefix
                        """)
                .param("namespace", namespace)
                .param("modelPrefix", modelPrefix + "%")
                .update();
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(embedding[i]));
        }
        return sb.append(']').toString();
    }
}
