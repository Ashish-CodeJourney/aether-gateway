package com.aether.gateway.acceptance.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Per TESTING-STRATEGY.md and ADR-009: the system under test runs as
 * real, separate OS processes (the built gateway-proxy and mock-provider
 * boot jars), against real Testcontainers-managed Postgres and Redis.
 * gateway-acceptance-tests has no project(...) dependency on gateway-core
 * or any other internal module (see this module's build.gradle.kts), so
 * every interaction below is over HTTP/process boundaries only, never a
 * shared JVM classpath with the system under test.
 *
 * <p>Two mock-provider instances are started ("primary" and "fallback")
 * so Phase 05 (M2) scenarios can exercise real failover; a generated
 * routing.yaml wires the gateway's "mock" route to both, at whatever
 * ports were actually allocated for this test run.
 */
public final class AcceptanceEnvironment {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;
    private static Process mockPrimaryProcess;
    private static Process mockFallbackProcess;
    private static Process gatewayProcess;
    private static int mockPrimaryPort;
    private static int mockFallbackPort;
    private static int gatewayPort;
    private static boolean started = false;

    private AcceptanceEnvironment() {
    }

    @SuppressWarnings("resource")
    public static synchronized void start() throws Exception {
        if (started) {
            return;
        }

        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("aether")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:8"))
                .withExposedPorts(6379);
        redis.start();

        mockPrimaryPort = findFreePort();
        mockPrimaryProcess = startJar(System.getProperty("mock.provider.jar"),
                Map.of("MOCK_PROVIDER_PORT", String.valueOf(mockPrimaryPort)));
        waitForHealthy(mockPrimaryBaseUrl());

        mockFallbackPort = findFreePort();
        mockFallbackProcess = startJar(System.getProperty("mock.provider.jar"),
                Map.of("MOCK_PROVIDER_PORT", String.valueOf(mockFallbackPort)));
        waitForHealthy(mockFallbackBaseUrl());

        Path routingConfig = writeRoutingConfig();

        gatewayPort = findFreePort();
        Map<String, String> gatewayEnv = new HashMap<>();
        gatewayEnv.put("GATEWAY_PORT", String.valueOf(gatewayPort));
        gatewayEnv.put("GATEWAY_DB_URL", "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/aether");
        gatewayEnv.put("GATEWAY_DB_USER", "postgres");
        gatewayEnv.put("GATEWAY_DB_PASSWORD", "postgres");
        gatewayEnv.put("SPRING_DATA_REDIS_HOST", "localhost");
        gatewayEnv.put("SPRING_DATA_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
        gatewayEnv.put("ROUTING_CONFIG_PATH", routingConfig.toAbsolutePath().toString());
        // Phase 06 (M3): pin the pessimistic per-request token estimate to
        // match mock-provider's default completion-token count (12,
        // MockChatController's fallback when no X-Mock-Tokens override is
        // present) exactly, rather than the larger, demo-oriented 500
        // production default. This matters beyond just letting scenarios
        // compute an exact budget: if estimate != actual, post-response
        // reconciliation (ChatCompletionController) adjusts the monthly
        // counter by the difference *after* each request completes, and
        // under the 100-concurrent-requests scenario that adjustment can
        // race ahead of other requests' still-pending reservation checks,
        // making "exactly 50 succeed" flaky. Estimate == actual makes
        // every reconciliation a no-op, so admission is decided purely,
        // deterministically, by the atomic reservation step.
        gatewayEnv.put("AETHER_QUOTA_ASSUMED_MAX_OUTPUT_TOKENS", "12");
        gatewayProcess = startJar(System.getProperty("gateway.proxy.jar"), gatewayEnv);
        waitForHealthy(gatewayBaseUrl());

        started = true;
    }

    public static synchronized void stop() {
        if (gatewayProcess != null) {
            gatewayProcess.destroy();
        }
        if (mockPrimaryProcess != null) {
            mockPrimaryProcess.destroy();
        }
        if (mockFallbackProcess != null) {
            mockFallbackProcess.destroy();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (redis != null) {
            redis.stop();
        }
        started = false;
    }

    public static String gatewayBaseUrl() {
        return "http://localhost:" + gatewayPort;
    }

    /** The provider M0/M1 scenarios exercise; same instance as {@link #mockPrimaryBaseUrl()}. */
    public static String mockProviderBaseUrl() {
        return mockPrimaryBaseUrl();
    }

    public static String mockPrimaryBaseUrl() {
        return "http://localhost:" + mockPrimaryPort;
    }

    public static String mockFallbackBaseUrl() {
        return "http://localhost:" + mockFallbackPort;
    }

    /**
     * Phase 06 (M3): inserts a test {@code api_key} row directly via JDBC
     * against the same Postgres the gateway process reads from, so
     * scenarios can exercise real quota enforcement end-to-end without
     * the (out-of-scope-for-M3) admin key-creation API. Hashes the raw
     * key with the same SHA-256 scheme as {@code ApiKeyHasher} in
     * gateway-quota; duplicated here rather than depending on that
     * module, per this module's black-box-only design.
     */
    public static void seedApiKey(String rawKey, Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        String hash = sha256Hex(rawKey);
        String jdbcUrl = "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/aether";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO api_key (id, name, key_hash, key_prefix, rps_limit, concurrency_limit, monthly_token_budget, enabled)
                     VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                     ON CONFLICT (key_hash) DO UPDATE SET
                         rps_limit = EXCLUDED.rps_limit,
                         concurrency_limit = EXCLUDED.concurrency_limit,
                         monthly_token_budget = EXCLUDED.monthly_token_budget
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "acceptance-test key");
            statement.setString(3, hash);
            statement.setString(4, rawKey.length() > 8 ? rawKey.substring(0, 8) : rawKey);
            setNullableInt(statement, 5, rpsLimit);
            setNullableInt(statement, 6, concurrencyLimit);
            setNullableLong(statement, 7, monthlyTokenBudget);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed api_key row", e);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String sha256Hex(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Path writeRoutingConfig() throws IOException {
        String yaml = """
                providers:
                  mock-primary:
                    baseUrl: http://localhost:%d
                  mock-fallback:
                    baseUrl: http://localhost:%d

                routes:
                  - alias: mock
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                      - provider: mock-fallback
                        model: mock
                """.formatted(mockPrimaryPort, mockFallbackPort);
        Path path = Files.createTempFile("aether-routing-", ".yaml");
        Files.writeString(path, yaml);
        path.toFile().deleteOnExit();
        return path;
    }

    private static Process startJar(String jarPath, Map<String, String> env) throws IOException {
        if (jarPath == null) {
            throw new IllegalStateException(
                    "Required system property for the boot jar path was not set; "
                            + "run via Gradle (gateway-acceptance-tests:test), not directly, "
                            + "since the jar paths are wired in gateway-acceptance-tests/build.gradle.kts");
        }
        ProcessBuilder builder = new ProcessBuilder("java", "-jar", jarPath);
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static void waitForHealthy(String baseUrl) throws InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/healthz"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not up yet, retry
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Service at " + baseUrl + " did not become healthy within 60 seconds");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
