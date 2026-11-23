package com.aether.gateway.cache;

import com.aether.gateway.core.port.CachePort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** F4: the exact-match cache contract against real Redis and Postgres, with an embedding model that must never be called. */
class CacheAdapterExactMatchContractIntegrationTest extends ExactMatchCacheContract {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static CacheAdapter cacheAdapter;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Path migrationsDir = Path.of("../../db/migrations").toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrationsDir).load().migrate();

        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();
        var connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "aether-onnx-cache-test");
        Files.createDirectories(cacheDir);
        // An Error, not a RuntimeException, so the adapter's fail-open
        // catch cannot swallow it and disguise an embedding call as a Miss.
        var refusingEmbeddingGenerator = new EmbeddingGenerator(cacheDir.toString()) {
            @Override
            public float[] embed(String text) {
                throw new AssertionError("exact-only caching must never compute an embedding");
            }
        };
        cacheAdapter = new CacheAdapter(
                new RedisExactMatchStore(redisTemplate), new PgVectorCacheStore(JdbcClient.create(dataSource)), refusingEmbeddingGenerator);
    }

    @AfterAll
    static void stopInfrastructure() {
        postgres.stop();
        redis.stop();
    }

    @Override
    protected CachePort cache() {
        return cacheAdapter;
    }

    @Override
    protected void advanceClockBy(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
