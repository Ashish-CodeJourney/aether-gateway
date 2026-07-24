package com.aether.gateway.cache;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4: real Postgres+pgvector, real Redis, and the real ONNX embedding
 * model end to end (no mocks for any of the three), per
 * TESTING-STRATEGY.md. Uses a lenient similarity threshold (0.5) for
 * the basic wiring assertions here; the actual tuned operating-point
 * threshold is chosen separately via the corpus sweep (task 12).
 */
class CacheAdapterIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static EmbeddingGenerator embeddingGenerator;
    private static JdbcClient jdbcClient;
    private static StringRedisTemplate redisTemplate;

    private CacheAdapter cacheAdapter;

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
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrationsDir)
                .load()
                .migrate();
        jdbcClient = JdbcClient.create(dataSource);

        redis = new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);
        redis.start();
        var connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        // Deliberately NOT a fresh temp dir per run: the ONNX model is a
        // ~90MB one-time download, and reusing a stable local cache
        // directory across test runs avoids re-downloading it every
        // time, consistent with how EmbeddingGenerator behaves in
        // production (see its javadoc).
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "aether-onnx-cache-test");
        Files.createDirectories(cacheDir);
        embeddingGenerator = new EmbeddingGenerator(cacheDir.toString());
        embeddingGenerator.warmUp();
    }

    @AfterAll
    static void stopInfrastructure() {
        postgres.stop();
        redis.stop();
    }

    @BeforeEach
    void freshAdapterPerTest() {
        cacheAdapter = new CacheAdapter(
                new RedisExactMatchStore(redisTemplate),
                new PgVectorCacheStore(jdbcClient),
                embeddingGenerator,
                0.5);
    }

    private ChatCompletionRequest requestFor(String content) {
        return new ChatCompletionRequest("mock", List.of(new ChatMessage("user", content)), false);
    }

    @Test
    void anIdenticalRequestIsAnExactHitAfterBeingStored() {
        String namespace = "ns-" + UUID.randomUUID();
        var request = requestFor("What's the capital of France?");
        cacheAdapter.store(namespace, request, "{\"answer\":\"Paris\"}", Duration.ofMinutes(10));

        CacheDecision decision = cacheAdapter.lookup(namespace, request, null, false);

        assertThat(decision).isInstanceOf(CacheDecision.ExactHit.class);
        assertThat(((CacheDecision.ExactHit) decision).responseBodyJson()).isEqualTo("{\"answer\":\"Paris\"}");
    }

    @Test
    void aGenuineParaphraseIsASemanticHit() {
        String namespace = "ns-" + UUID.randomUUID();
        var seed = requestFor("What's the capital of France?");
        cacheAdapter.store(namespace, seed, "{\"answer\":\"Paris\"}", Duration.ofMinutes(10));

        var paraphrase = requestFor("Can you tell me France's capital city?");
        CacheDecision decision = cacheAdapter.lookup(namespace, paraphrase, null, false);

        assertThat(decision).isInstanceOf(CacheDecision.SemanticHit.class);
        var hit = (CacheDecision.SemanticHit) decision;
        // Postgres's JSONB column canonicalises the text it stores (it
        // may add or remove whitespace); this path reads back through
        // JSONB, unlike the exact-hit path above which reads through
        // Redis's raw string cache, so an exact byte-for-byte comparison
        // isn't the right assertion here. Whitespace-insensitive
        // comparison is sufficient to prove the right value round-tripped.
        assertThat(hit.responseBodyJson().replaceAll("\\s+", "")).isEqualTo("{\"answer\":\"Paris\"}");
        assertThat(hit.similarity()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void anEntityMismatchIsRejectedByTheGuardEvenIfSimilarityWouldOtherwisePass() {
        String namespace = "ns-" + UUID.randomUUID();
        var seed = requestFor("What's the capital of France?");
        cacheAdapter.store(namespace, seed, "{\"answer\":\"Paris\"}", Duration.ofMinutes(10));

        var adversarial = requestFor("What's the capital of Germany?");
        CacheDecision decision = cacheAdapter.lookup(namespace, adversarial, null, false);

        assertThat(decision).as("entity guard must block this hit regardless of embedding similarity")
                .isNotInstanceOf(CacheDecision.SemanticHit.class)
                .isNotInstanceOf(CacheDecision.ExactHit.class);
    }

    @Test
    void cacheEntriesNeverCrossNamespaces() {
        String namespaceA = "tenant-a-" + UUID.randomUUID();
        String namespaceB = "tenant-b-" + UUID.randomUUID();
        var request = requestFor("Summarise this contract for me");
        cacheAdapter.store(namespaceA, request, "{\"answer\":\"summary\"}", Duration.ofMinutes(10));

        CacheDecision decisionForB = cacheAdapter.lookup(namespaceB, request, null, false);

        assertThat(decisionForB).isInstanceOf(CacheDecision.Miss.class);
    }

    @Test
    void bypassesWithoutEverTouchingTheStoreWhenTemperatureIsHigh() {
        String namespace = "ns-" + UUID.randomUUID();
        var highTempRequest = new ChatCompletionRequest(
                "mock", List.of(new ChatMessage("user", "surprise me")), false, 0.9, null);

        CacheDecision decision = cacheAdapter.lookup(namespace, highTempRequest, null, false);

        assertThat(decision).isInstanceOf(CacheDecision.Bypass.class);
        assertThat(((CacheDecision.Bypass) decision).reason()).isEqualTo("temperature_too_high");
    }

    @Test
    void unrelatedPromptsAreAMiss() {
        String namespace = "ns-" + UUID.randomUUID();
        var seed = requestFor("What's the capital of France?");
        cacheAdapter.store(namespace, seed, "{\"answer\":\"Paris\"}", Duration.ofMinutes(10));

        var unrelated = requestFor("What's a good recipe for banana bread?");
        CacheDecision decision = cacheAdapter.lookup(namespace, unrelated, null, false);

        assertThat(decision).isInstanceOf(CacheDecision.Miss.class);
    }

    @Test
    void failsOpenWhenTheStoreIsUnreachable() {
        var brokenConnectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        brokenConnectionFactory.afterPropertiesSet();
        var brokenRedisTemplate = new StringRedisTemplate(brokenConnectionFactory);
        brokenRedisTemplate.afterPropertiesSet();
        var brokenAdapter = new CacheAdapter(
                new RedisExactMatchStore(brokenRedisTemplate),
                new PgVectorCacheStore(jdbcClient),
                embeddingGenerator,
                0.5);

        CacheDecision decision = brokenAdapter.lookup("ns-broken", requestFor("hello"), null, false);

        assertThat(decision).as("a store outage must fail open (Miss), never throw").isInstanceOf(CacheDecision.Miss.class);
        brokenConnectionFactory.destroy();
    }
}
