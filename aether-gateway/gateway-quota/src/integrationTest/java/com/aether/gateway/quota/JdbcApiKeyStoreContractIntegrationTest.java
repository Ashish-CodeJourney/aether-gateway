package com.aether.gateway.quota;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;

/** F8.1: the API key store contract against the real db/migrations schema and a real Postgres, no mocking. */
class JdbcApiKeyStoreContractIntegrationTest extends ApiKeyStoreContract {

    private static PostgreSQLContainer<?> postgres;
    private static ApiKeyStore store;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether_apikey_admin_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Path migrationsDir = Path.of("../../db/migrations").toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrationsDir).load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        store = new ApiKeyStore(new JdbcApiKeyRepository(jdbcClient), new JdbcApiKeyAdminRepository(jdbcClient));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Override
    protected ApiKeyStore store() {
        return store;
    }
}
