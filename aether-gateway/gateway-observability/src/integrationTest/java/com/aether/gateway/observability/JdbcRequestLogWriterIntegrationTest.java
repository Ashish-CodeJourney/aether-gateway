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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6.2: proves the async request log writer actually persists to the
 * real request_log table (real Postgres, real Flyway migrations, not
 * mocked), including the trickier column types (uuid, text[],
 * timestamptz) that earlier phases found real JDBC binding bugs in
 * during M4.
 */
class JdbcRequestLogWriterIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbcClient;
    private static JdbcRequestLogWriter writer;

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
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrationsDir)
                .load()
                .migrate();
        jdbcClient = JdbcClient.create(dataSource);
        writer = new JdbcRequestLogWriter(jdbcClient);
    }

    @AfterAll
    static void stopInfrastructure() {
        writer.shutdown();
        postgres.stop();
    }

    @Test
    void writesAnEntryAsynchronouslyAndItLandsInTheRealTable() throws InterruptedException {
        UUID id = UUID.randomUUID();
        var entry = new RequestLogEntry(
                id,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), // the system anonymous key, V5 migration
                "trace-abc",
                "mock",
                "mock-primary",
                "mock",
                null,
                null,
                false,
                "MISS",
                null,
                12,
                34,
                new BigDecimal("0.000123"),
                new BigDecimal("0.000000"),
                50,
                120,
                1,
                List.of("mock-primary"),
                "OK",
                null,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        long before = System.nanoTime();
        writer.log(entry);
        long callReturnedAfterNanos = System.nanoTime() - before;

        assertThat(TimeUnit.NANOSECONDS.toMillis(callReturnedAfterNanos))
                .as("log() must return immediately, never block on the actual DB write")
                .isLessThan(50);

        Object[] row = pollUntilFound(id);

        assertThat(row[0]).isEqualTo("mock-primary");
        assertThat(row[1]).isEqualTo("mock");
        assertThat(row[2]).isEqualTo("MISS");
        assertThat(row[3]).isEqualTo(12);
        assertThat(row[4]).isEqualTo(34);
        assertThat(((BigDecimal) row[5]).doubleValue()).isCloseTo(0.000123, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat((String[]) row[6]).containsExactly("mock-primary");
        assertThat(row[7]).isEqualTo("OK");
    }

    private Object[] pollUntilFound(UUID id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbcClient.sql("SELECT * FROM request_log WHERE id = :id")
                    .param("id", id)
                    .query((rs, rowNum) -> new Object[] {
                            rs.getString("provider"),
                            rs.getString("model"),
                            rs.getString("cache_outcome"),
                            rs.getInt("input_tokens"),
                            rs.getInt("output_tokens"),
                            rs.getBigDecimal("cost_usd"),
                            rs.getArray("failover_chain").getArray(),
                            rs.getString("status"),
                    })
                    .list();
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("request_log row for id " + id + " never appeared within 5 seconds");
    }

    @Test
    void aFailedWriteDoesNotCrashTheWriterThread() throws InterruptedException {
        // An entry with a non-existent api_key_id violates the FK
        // constraint; the writer must log and move on, not die, so
        // subsequent valid entries still get written.
        UUID badId = UUID.randomUUID();
        var badEntry = new RequestLogEntry(
                badId, UUID.randomUUID(), null, "mock", "mock-primary", "mock", null, null, false,
                "MISS", null, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, 1, 1, 1, List.of(), "OK", null, Instant.now());
        writer.log(badEntry);

        UUID goodId = UUID.randomUUID();
        var goodEntry = new RequestLogEntry(
                goodId, UUID.fromString("00000000-0000-0000-0000-000000000000"), null, "mock", "mock-primary", "mock",
                null, null, false, "MISS", null, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, 1, 1, 1, List.of(), "OK", null,
                Instant.now());
        writer.log(goodEntry);

        long deadline = System.currentTimeMillis() + 5000;
        int count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbcClient.sql("SELECT count(*) FROM request_log WHERE id = :id")
                    .param("id", goodId)
                    .query(Integer.class)
                    .single();
            if (count == 1) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(count).isEqualTo(1);
    }
}
