package com.aether.gateway.registry;

import com.aether.gateway.core.port.PromptRegistryPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * F7.1-F7.4: the prompt registry contract against the real
 * db/migrations/V4__prompt_registry.sql schema and a real Postgres
 * (Testcontainers), no mocking.
 */
class JdbcPromptRegistryContractIntegrationTest extends PromptRegistryContract {

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

    @Override
    protected PromptRegistryPort registry() {
        return registry;
    }
}
