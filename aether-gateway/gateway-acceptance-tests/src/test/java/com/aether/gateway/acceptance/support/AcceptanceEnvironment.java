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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
