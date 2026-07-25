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
        routingConfigPath = routingConfig;

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
        // Phase 07 (M4): point at the same shared, stable ONNX cache
        // directory used elsewhere in this project (see
        // gateway-cache's CacheAdapterIntegrationTest), so the
        // acceptance suite reuses the already-downloaded model instead
        // of re-fetching the ~90MB file on every run.
        gatewayEnv.put("AETHER_CACHE_ONNX_RESOURCE_CACHE_DIR",
                Path.of(System.getProperty("java.io.tmpdir"), "aether-onnx-cache").toString());
        // Phase 08 (M5): the checked-in cost-model.yaml, by absolute
        // path - the default relative path ("cost-model.yaml") would
        // resolve against this JVM's own working directory
        // (gateway-acceptance-tests/), not the repo root where the file
        // actually lives, and CostModelRepository throws on a missing
        // file at construction time, which would otherwise crash the
        // spawned gateway process before it ever started serving.
        gatewayEnv.put("COST_MODEL_CONFIG_PATH",
                Path.of("../cost-model.yaml").toAbsolutePath().normalize().toString());
        // Phase 10 (M7): F8.5 admin auth is fail-closed with no key
        // configured, so every scenario calling an /admin/** endpoint
        // needs this - a fixed test-only value, never a production secret.
        gatewayEnv.put("AETHER_ADMIN_API_KEY", ADMIN_API_KEY);
        // Phase 12 (M9): F9.5's per-IP anonymous rate limit exists for a
        // real anonymous client, not for this test harness - every
        // scenario in this suite that omits Authorization (the
        // established ANONYMOUS_NAMESPACE convention since M3) shares
        // one IP bucket (127.0.0.1, the whole suite running as one
        // process), and the production default is real enough to
        // legitimately 429 unrelated scenarios partway through a run.
        // Confirmed live: this broke ~20 otherwise-unrelated scenarios
        // (semantic cache, cost visibility, prompt rollback) with
        // "expected 200 but was 429" before this override was added.
        gatewayEnv.put("AETHER_SECURITY_ANONYMOUS_IP_RPS_LIMIT", "10000");
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

    /** Test-only fixed admin key; see the F8.5 comment where it's passed to the spawned gateway process. */
    private static final String ADMIN_API_KEY = "test-admin-key-do-not-use-in-production";

    public static String adminApiKey() {
        return ADMIN_API_KEY;
    }

    private static Path routingConfigPath;

    /** Phase 12 (M9): the real file backing {@code ROUTING_CONFIG_PATH} for the spawned gateway - scenarios rewrite it to exercise hot reload (F2.7) and reload rejection (F9.3). */
    public static Path routingConfigPath() {
        return routingConfigPath;
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

    /**
     * Phase 08 (M5) / F6.2: request_log is written asynchronously by
     * design (never blocking the response path), so scenarios must poll
     * rather than assume the row is already there the instant the HTTP
     * response comes back. Returns the single most recently created row
     * - correct as long as a scenario sends requests one at a time and
     * checks the log immediately after each, which is how every M5
     * scenario here is written.
     */
    public static Map<String, Object> mostRecentRequestLogEntry() {
        String jdbcUrl = "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/aether";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM request_log ORDER BY created_at DESC LIMIT 1")) {
                var resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    Map<String, Object> row = new HashMap<>();
                    var metaData = resultSet.getMetaData();
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                    }
                    return row;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query request_log", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("No request_log row appeared within 5 seconds");
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
                    cache:
                      enabled: true
                      threshold: 0.94
                      ttl: 6h
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
