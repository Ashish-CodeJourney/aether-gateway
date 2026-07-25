package com.aether.gateway.registry;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7.1-F7.4, against the real db/migrations/V4__prompt_registry.sql
 * schema and a real Postgres (Testcontainers), no mocking. The last
 * test is this phase's exit criterion at the port level: re-pointing an
 * alias takes effect on the very next lookup, no restart of anything.
 */
class JdbcPromptRegistryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcPromptRegistry registry;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether_registry_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Path migrationsDir = Path.of("../../db/migrations").toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrationsDir)
                .load()
                .migrate();
        registry = new JdbcPromptRegistry(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void createsAPromptAndVersionAndFindsItByVersionNumber() {
        String promptName = "greeting-" + UUID.randomUUID();
        registry.createPrompt(promptName);

        List<ChatMessage> template = List.of(new ChatMessage("user", "Hello {{name}}."));
        PromptVersion created = registry.createVersion(promptName, template, Set.of("name"), null);

        assertThat(created.version()).isEqualTo(1);

        Optional<PromptVersion> found = registry.findByNameAndVersion(promptName, 1);
        assertThat(found).isPresent();
        assertThat(found.get().template()).containsExactly(new ChatMessage("user", "Hello {{name}}."));
        assertThat(found.get().variables()).containsExactly("name");
    }

    @Test
    void versionNumbersIncrementPerPrompt() {
        String promptName = "counter-" + UUID.randomUUID();
        registry.createPrompt(promptName);

        PromptVersion v1 = registry.createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);
        PromptVersion v2 = registry.createVersion(promptName, List.of(new ChatMessage("user", "v2")), Set.of(), null);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
    }

    @Test
    void findByNameAndVersionIsEmptyForAnUnknownVersion() {
        String promptName = "lonely-" + UUID.randomUUID();
        registry.createPrompt(promptName);
        registry.createVersion(promptName, List.of(new ChatMessage("user", "hi")), Set.of(), null);

        assertThat(registry.findByNameAndVersion(promptName, 99)).isEmpty();
    }

    @Test
    void setAliasThenFindByAliasResolvesToThePointedVersion() {
        String promptName = "aliased-" + UUID.randomUUID();
        registry.createPrompt(promptName);
        PromptVersion v1 = registry.createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);

        registry.setAlias(promptName, "production", v1.version());

        Optional<PromptVersion> resolved = registry.findByNameAndAlias(promptName, "production");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().version()).isEqualTo(1);
    }

    /** F7.4's exit criterion at the port level: rollback is re-pointing, takes effect immediately, no restart. */
    @Test
    void repointingAnAliasTakesEffectImmediatelyOnTheNextLookup() {
        String promptName = "rollback-" + UUID.randomUUID();
        registry.createPrompt(promptName);
        PromptVersion v1 = registry.createVersion(promptName, List.of(new ChatMessage("user", "version one")), Set.of(), null);
        PromptVersion v2 = registry.createVersion(promptName, List.of(new ChatMessage("user", "version two")), Set.of(), null);

        registry.setAlias(promptName, "production", v2.version());
        assertThat(registry.findByNameAndAlias(promptName, "production")).get()
                .extracting(PromptVersion::version).isEqualTo(2);

        registry.setAlias(promptName, "production", v1.version());
        assertThat(registry.findByNameAndAlias(promptName, "production")).get()
                .extracting(PromptVersion::version).isEqualTo(1);
    }
}
