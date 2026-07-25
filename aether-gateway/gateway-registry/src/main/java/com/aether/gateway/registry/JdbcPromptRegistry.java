package com.aether.gateway.registry;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.port.PromptRegistryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** F7.1-F7.4: JdbcClient against db/migrations/V4__prompt_registry.sql, no ORM (ADR-007's convention applied here too). */
public class JdbcPromptRegistry implements PromptRegistryPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbcClient;

    public JdbcPromptRegistry(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public String createPrompt(String promptName) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO prompt (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", promptName)
                .update();
        return id.toString();
    }

    @Override
    public PromptVersion createVersion(String promptName, List<ChatMessage> template, Set<String> variables, String modelDefaultsJson) {
        UUID promptId = promptIdFor(promptName);
        int nextVersion = jdbcClient.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM prompt_version WHERE prompt_id = :promptId")
                .param("promptId", promptId)
                .query(Integer.class)
                .single();

        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO prompt_version (id, prompt_id, version, template, variables, model_defaults)
                        VALUES (:id, :promptId, :version, :template::jsonb, :variables, :modelDefaults::jsonb)
                        """)
                .param("id", id)
                .param("promptId", promptId)
                .param("version", nextVersion)
                .param("template", toJson(template))
                .param("variables", variables.toArray(new String[0]))
                .param("modelDefaults", modelDefaultsJson)
                .update();

        return findByNameAndVersion(promptName, nextVersion)
                .orElseThrow(() -> new IllegalStateException("Just-inserted prompt version not found: " + promptName + "@" + nextVersion));
    }

    @Override
    public void setAlias(String promptName, String alias, int version) {
        UUID promptId = promptIdFor(promptName);
        jdbcClient.sql("""
                        INSERT INTO prompt_alias (prompt_id, alias, version)
                        VALUES (:promptId, :alias, :version)
                        ON CONFLICT (prompt_id, alias) DO UPDATE SET version = EXCLUDED.version
                        """)
                .param("promptId", promptId)
                .param("alias", alias)
                .param("version", version)
                .update();
    }

    @Override
    public Optional<PromptVersion> findByNameAndVersion(String promptName, int version) {
        return jdbcClient.sql("""
                        SELECT p.id AS prompt_id, p.name AS prompt_name, pv.version, pv.template,
                               pv.variables, pv.model_defaults, pv.created_at
                        FROM prompt_version pv
                        JOIN prompt p ON p.id = pv.prompt_id
                        WHERE p.name = :promptName AND pv.version = :version
                        """)
                .param("promptName", promptName)
                .param("version", version)
                .query(this::toPromptVersion)
                .optional();
    }

    @Override
    public Optional<PromptVersion> findByNameAndAlias(String promptName, String alias) {
        return jdbcClient.sql("""
                        SELECT p.id AS prompt_id, p.name AS prompt_name, pv.version, pv.template,
                               pv.variables, pv.model_defaults, pv.created_at
                        FROM prompt_alias pa
                        JOIN prompt p ON p.id = pa.prompt_id
                        JOIN prompt_version pv ON pv.prompt_id = pa.prompt_id AND pv.version = pa.version
                        WHERE p.name = :promptName AND pa.alias = :alias
                        """)
                .param("promptName", promptName)
                .param("alias", alias)
                .query(this::toPromptVersion)
                .optional();
    }

    private UUID promptIdFor(String promptName) {
        return jdbcClient.sql("SELECT id FROM prompt WHERE name = :name")
                .param("name", promptName)
                .query(UUID.class)
                .single();
    }

    private PromptVersion toPromptVersion(java.sql.ResultSet rs, int rowNum) throws SQLException {
        List<ChatMessage> template = fromJsonTemplate(rs.getString("template"));
        String[] variablesArray = (String[]) rs.getArray("variables").getArray();
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        return new PromptVersion(
                rs.getString("prompt_id"),
                rs.getString("prompt_name"),
                rs.getInt("version"),
                template,
                Set.of(variablesArray),
                rs.getString("model_defaults"),
                createdAt);
    }

    private static String toJson(List<ChatMessage> template) {
        return JSON.writeValueAsString(template);
    }

    private static List<ChatMessage> fromJsonTemplate(String json) {
        return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
    }
}
