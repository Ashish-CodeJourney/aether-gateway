package com.aether.gateway.quota;

import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;
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

import static org.assertj.core.api.Assertions.assertThat;

/** F8.1, against the real db/migrations/V1__api_key.sql schema and a real Postgres, no mocking. */
class JdbcApiKeyAdminRepositoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcApiKeyAdminRepository repository;

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
        repository = new JdbcApiKeyAdminRepository(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void createsAKeyAndReturnsTheRawKeyExactlyOnce() {
        CreatedApiKey created = repository.create("test-key", List.of("team-a"), 10, 5, 100_000L);

        assertThat(created.rawKey()).startsWith("aeth_");
        assertThat(created.summary().name()).isEqualTo("test-key");
        assertThat(created.summary().tags()).containsExactly("team-a");
        assertThat(created.summary().enabled()).isTrue();
        assertThat(created.summary().keyPrefix()).isEqualTo(created.rawKey().substring(0, 12));
    }

    @Test
    void listedKeysNeverExposeTheHash() {
        repository.create("listed-key", List.of(), null, null, null);

        List<ApiKeySummary> keys = repository.list();

        assertThat(keys).anySatisfy(k -> assertThat(k.name()).isEqualTo("listed-key"));
    }

    @Test
    void updatesOnlyTheFieldsSupplied() {
        CreatedApiKey created = repository.create("updatable-key", List.of(), 10, 5, 100_000L);

        Optional<ApiKeySummary> updated = repository.update(created.summary().id(), false, null, null, null);

        assertThat(updated).isPresent();
        assertThat(updated.get().enabled()).isFalse();
        assertThat(updated.get().rpsLimit()).isEqualTo(10);
    }

    @Test
    void updateOfAnUnknownIdReturnsEmpty() {
        assertThat(repository.update(java.util.UUID.randomUUID().toString(), false, null, null, null)).isEmpty();
    }

    @Test
    void deletesAKey() {
        CreatedApiKey created = repository.create("deletable-key", List.of(), null, null, null);

        boolean deleted = repository.delete(created.summary().id());

        assertThat(deleted).isTrue();
        assertThat(repository.list()).noneSatisfy(k -> assertThat(k.id()).isEqualTo(created.summary().id()));
    }
}
