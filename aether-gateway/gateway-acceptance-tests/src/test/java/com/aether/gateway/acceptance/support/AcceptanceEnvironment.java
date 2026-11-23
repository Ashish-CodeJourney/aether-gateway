package com.aether.gateway.acceptance.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per TESTING-STRATEGY.md and ADR-009: the system under test runs as
 * real, separate OS processes (the built gateway-proxy and mock-provider
 * boot jars), against real Testcontainers-managed Postgres and Redis -
 * or, in lite mode (see {@link #isLiteMode()}), against no
 * infrastructure at all.
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
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final GatewayClient ADMIN_CLIENT = new GatewayClient();

    private AcceptanceEnvironment() {
    }

    @SuppressWarnings("resource")
    public static synchronized void start() throws Exception {
        if (started) {
            return;
        }

        if (!isLiteMode()) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("aether")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();

            redis = new GenericContainer<>(DockerImageName.parse("redis:8"))
                    .withExposedPorts(6379);
            redis.start();
        }

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
        if (isLiteMode()) {
            gatewayEnv.put("SPRING_PROFILES_ACTIVE", "lite");
        } else {
            gatewayEnv.put("GATEWAY_DB_URL", "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/aether");
            gatewayEnv.put("GATEWAY_DB_USER", "postgres");
            gatewayEnv.put("GATEWAY_DB_PASSWORD", "postgres");
            gatewayEnv.put("SPRING_DATA_REDIS_HOST", "localhost");
            gatewayEnv.put("SPRING_DATA_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
        }
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
        // Anonymous access is off by default, since an unmetered request
        // path on a reachable gateway is an open proxy to the operator's
        // provider credentials. Most scenarios here predate API keys and
        // deliberately send no Authorization header (the
        // ANONYMOUS_NAMESPACE convention above), so the suite opts back
        // in explicitly rather than silently relying on the default.
        gatewayEnv.put("AETHER_SECURITY_ALLOW_ANONYMOUS", "true");
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

    /**
     * ADR-010: {@code -Daether.acceptance.mode=lite} (the
     * {@code acceptanceTestLite} Gradle task) runs the same suite against
     * a gateway started with the lite profile and no Postgres or Redis
     * at all; anything else runs it against full mode.
     */
    public static boolean isLiteMode() {
        return "lite".equals(System.getProperty("aether.acceptance.mode"));
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
     * Creates an API key through the admin API, exactly as an operator
     * would, and returns its raw key. Black-box on purpose: the suite
     * never reaches into the gateway's own store, so it runs unchanged
     * against whichever store the gateway is configured with.
     */
    public static String createApiKey(Integer rpsLimit, Integer concurrencyLimit, Long monthlyTokenBudget) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "acceptance-test key");
        body.put("rps_limit", rpsLimit);
        body.put("concurrency_limit", concurrencyLimit);
        body.put("monthly_token_budget", monthlyTokenBudget);
        HttpResponse<String> response = adminCall(() -> ADMIN_CLIENT.postJson(
                gatewayBaseUrl() + "/admin/api-keys", JSON.writeValueAsString(body), null, adminHeaders()));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Creating an API key failed: " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body()).get("raw_key").asText();
    }

    /**
     * Phase 08 (M5) / F6.2: the request log is written asynchronously by
     * design (never blocking the response path), so scenarios must poll
     * rather than assume the entry is already there the instant the HTTP
     * response comes back. Returns the most recent entry, read through
     * {@code GET /admin/requests} - correct as long as a scenario sends
     * requests one at a time and checks the log immediately after each,
     * which is how every M5 scenario here is written. Keys are the API's
     * snake_case field names, which match the request_log columns.
     */
    public static Map<String, Object> mostRecentRequestLogEntry() {
        return mostRecentRequestLogEntryCreatedAfter(Instant.EPOCH);
    }

    /**
     * As {@link #mostRecentRequestLogEntry()}, but waits for an entry
     * written after {@code since}: a streamed request's entry is written
     * when the stream ends, which can be just after the client has read
     * it, so the newest entry at that moment may still be the previous
     * scenario's.
     */
    public static Map<String, Object> mostRecentRequestLogEntryCreatedAfter(Instant since) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Map<String, Object>> newest = newestRequestLogEntry()
                    .filter(entry -> Instant.parse((String) entry.get("created_at")).isAfter(since));
            if (newest.isPresent()) {
                return newest.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("No request log entry appeared within 5 seconds");
    }

    private static Optional<Map<String, Object>> newestRequestLogEntry() {
        HttpResponse<String> response = adminCall(() -> ADMIN_CLIENT.get(gatewayBaseUrl() + "/admin/requests?limit=1", adminHeaders()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Reading the request log failed: " + response.statusCode() + " " + response.body());
        }
        List<Map<String, Object>> entries = JSON.readValue(response.body(), new TypeReference<>() {
        });
        return entries.stream().findFirst();
    }

    private interface AdminCall {
        HttpResponse<String> send() throws Exception;
    }

    private static HttpResponse<String> adminCall(AdminCall call) {
        try {
            return call.send();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the admin API", e);
        } catch (Exception e) {
            throw new IllegalStateException("Calling the admin API failed", e);
        }
    }

    private static Map<String, String> adminHeaders() {
        return Map.of("X-Aether-Admin-Key", ADMIN_API_KEY);
    }

    private static Path writeRoutingConfig() throws IOException {
        String yaml = """
                providers:
                  mock-primary:
                    baseUrl: http://localhost:%d
                  mock-fallback:
                    baseUrl: http://localhost:%d
                  echo:
                    type: echo

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
                  - alias: mock-lenient-cache
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                    cache:
                      enabled: true
                      threshold: 0.5
                      ttl: 6h
                  - alias: mock-exact-only-cache
                    chain:
                      - provider: mock-primary
                        model: mock
                        weight: 100
                    cache:
                      enabled: true
                      semantic: false
                      threshold: 0.5
                      ttl: 6h
                  - alias: echo
                    chain:
                      - provider: echo
                        model: echo-1
                    cache:
                      enabled: true
                      semantic: false
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
