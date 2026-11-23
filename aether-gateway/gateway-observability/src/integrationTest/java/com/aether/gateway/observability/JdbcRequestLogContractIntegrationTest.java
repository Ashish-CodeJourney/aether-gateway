package com.aether.gateway.observability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

/** F6.2 / F8.4: the request log contract against the real partitioned request_log table in a real Postgres. */
class JdbcRequestLogContractIntegrationTest extends RequestLogContract {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbcClient;
    private static JdbcRequestLogWriter writer;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether_request_log_contract")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Path migrationsDir = Path.of("../../db/migrations").toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrationsDir).load().migrate();
        jdbcClient = JdbcClient.create(dataSource);
        writer = new JdbcRequestLogWriter(jdbcClient);
    }

    @AfterAll
    static void stopPostgres() {
        writer.shutdown();
        postgres.stop();
    }

    @Override
    protected RequestLogStore emptyStore() {
        jdbcClient.sql("TRUNCATE request_log").update();
        return new RequestLogStore(writer, new JdbcRequestLogQueryRepository(jdbcClient), new JdbcUsageQueryRepository(jdbcClient));
    }

    @Override
    protected UUID registeredApiKeyId() {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO api_key (id, name, key_hash, key_prefix) VALUES (:id, 'contract', :hash, 'contract')")
                .param("id", id)
                .param("hash", "hash-" + id)
                .update();
        return id;
    }
}
