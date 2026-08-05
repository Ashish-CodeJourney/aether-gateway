package com.aether.gateway.observability;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * request_log is RANGE-partitioned by created_at and V2 only ships the
 * two partitions that existed when it was written, so every insert past
 * the last one fails outright - a hard, dated production outage rather
 * than a degradation. This proves the maintainer keeps a rolling window
 * of future partitions open, idempotently and safely when several
 * replicas run it at once.
 */
class RequestLogPartitionMaintainerIntegrationTest {

    private static final UUID ANONYMOUS_KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbcClient;

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
    }

    @AfterAll
    static void stopInfrastructure() {
        postgres.stop();
    }

    @Test
    void opensAPartitionForEveryMonthInTheConfiguredLookaheadWindow() {
        var maintainer = new RequestLogPartitionMaintainer(jdbcClient, 3);

        maintainer.ensurePartitions();

        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        assertThat(partitionNames())
                .contains(
                        partitionName(current),
                        partitionName(current.plusMonths(1)),
                        partitionName(current.plusMonths(2)),
                        partitionName(current.plusMonths(3)));
    }

    @Test
    void acceptsAnInsertDatedBeyondTheLastPartitionShippedByTheMigrations() {
        var maintainer = new RequestLogPartitionMaintainer(jdbcClient, 6);
        maintainer.ensurePartitions();

        Instant sixMonthsOut = YearMonth.now(ZoneOffset.UTC)
                .plusMonths(6)
                .atDay(15)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        UUID id = UUID.randomUUID();

        insertRequestLogRow(id, sixMonthsOut);

        assertThat(countRequestLogRows(id)).isEqualTo(1);
    }

    @Test
    void createsNothingOnASecondRunOverTheSameWindow() {
        var maintainer = new RequestLogPartitionMaintainer(jdbcClient, 2);
        maintainer.ensurePartitions();

        int createdOnSecondRun = maintainer.ensurePartitions();

        assertThat(createdOnSecondRun).isZero();
    }

    @Test
    void toleratesSeveralReplicasMaintainingPartitionsConcurrently() throws Exception {
        var maintainer = new RequestLogPartitionMaintainer(jdbcClient, 12);
        int replicas = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(replicas)) {
            List<Callable<Integer>> runs = java.util.Collections.nCopies(replicas, maintainer::ensurePartitions);
            int totalCreated = 0;
            for (var future : pool.invokeAll(runs)) {
                totalCreated += future.get();
            }

            // Every month in the window gets created exactly once
            // regardless of how many replicas raced for it; a partition
            // created twice would have thrown instead.
            assertThat(totalCreated).isLessThanOrEqualTo(13);
        }

        YearMonth twelveOut = YearMonth.now(ZoneOffset.UTC).plusMonths(12);
        assertThat(partitionNames()).contains(partitionName(twelveOut));
    }

    private static String partitionName(YearMonth month) {
        return "request_log_%d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private List<String> partitionNames() {
        return jdbcClient.sql("""
                        SELECT child.relname
                        FROM pg_inherits
                        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
                        JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                        WHERE parent.relname = 'request_log'
                        """)
                .query(String.class)
                .list();
    }

    private void insertRequestLogRow(UUID id, Instant createdAt) {
        jdbcClient.sql("""
                        INSERT INTO request_log (id, api_key_id, streamed, cache_outcome, status, created_at)
                        VALUES (:id, :apiKeyId, false, 'MISS', 'OK', :createdAt)
                        """)
                .param("id", id)
                .param("apiKeyId", ANONYMOUS_KEY_ID)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    private int countRequestLogRows(UUID id) {
        return jdbcClient.sql("SELECT count(*) FROM request_log WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    @Test
    void keepsHistoricPartitionsCreatedByTheMigrations() {
        new RequestLogPartitionMaintainer(jdbcClient, 1).ensurePartitions();

        assertThat(partitionNames()).contains("request_log_2026_07", "request_log_2026_08");
    }

    @Test
    void backfillsAPartitionForAMonthThatAlreadyPassed() {
        var maintainer = new RequestLogPartitionMaintainer(jdbcClient, 1);
        LocalDate lastMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1);

        maintainer.ensurePartitionForMonthOf(lastMonth);

        assertThat(partitionNames()).contains(partitionName(YearMonth.from(lastMonth)));
    }
}
