package com.aether.gateway.observability;

import com.aether.gateway.core.domain.RequestLogEntry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real bug found while wiring the console's request explorer screen
 * (Phase 10/M7): {@code similarity} is a Postgres {@code real} (float4)
 * column, and this driver version's {@code getObject(col,
 * Double.class)} rejects that conversion outright ("conversion to
 * class java.lang.Double from float4 not supported") - discovered live
 * against the real docker-compose gateway, not caught by
 * {@code JdbcRequestLogWriterIntegrationTest} since that test never
 * reads {@code similarity} back out. This test covers both a row with
 * a real similarity value (a semantic cache hit) and one without
 * (a miss), so the null-handling path is proven too, not just the
 * happy path.
 */
class JdbcRequestLogQueryRepositoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcRequestLogWriter writer;
    private static JdbcRequestLogQueryRepository queryRepository;

    @BeforeAll
    static void startInfrastructure() {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Path migrationsDir = Path.of("../../db/migrations").toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrationsDir).load().migrate();

        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        writer = new JdbcRequestLogWriter(jdbcClient);
        queryRepository = new JdbcRequestLogQueryRepository(jdbcClient);
    }

    @AfterAll
    static void stopInfrastructure() {
        writer.shutdown();
        postgres.stop();
    }

    @Test
    void readsBackAnEntryWithARealSimilarityValue() throws InterruptedException {
        UUID id = UUID.randomUUID();
        writer.log(entry(id, "SEMANTIC_HIT", 0.97));
        List<RequestLogEntry> found = pollUntilFound(id);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).similarity()).isCloseTo(0.97, org.assertj.core.data.Offset.offset(0.001));
        assertThat(found.get(0).cacheOutcome()).isEqualTo("SEMANTIC_HIT");
    }

    @Test
    void readsBackAnEntryWithNoSimilarityValue() throws InterruptedException {
        UUID id = UUID.randomUUID();
        writer.log(entry(id, "MISS", null));
        List<RequestLogEntry> found = pollUntilFound(id);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).similarity()).isNull();
        assertThat(found.get(0).cacheOutcome()).isEqualTo("MISS");
    }

    private static RequestLogEntry entry(UUID id, String cacheOutcome, Double similarity) {
        return new RequestLogEntry(
                id,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                null, "mock", "mock-primary", "mock", null, null, false,
                cacheOutcome, similarity, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, 1, 1, 1, List.of(), "OK", null,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private List<RequestLogEntry> pollUntilFound(UUID id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<RequestLogEntry> matches = queryRepository.recent(200).stream()
                    .filter(e -> e.id().equals(id))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("request_log row for id " + id + " never appeared within 5 seconds");
    }
}
