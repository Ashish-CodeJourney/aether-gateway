package com.aether.gateway.proxy.config;

import com.aether.gateway.cache.CacheAdapter;
import com.aether.gateway.cache.EmbeddingGenerator;
import com.aether.gateway.cache.PgVectorCacheStore;
import com.aether.gateway.cache.RedisExactMatchStore;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.core.port.RequestLogQueryPort;
import com.aether.gateway.core.port.UsageQueryPort;
import com.aether.gateway.observability.CostModelRepository;
import com.aether.gateway.observability.CostModelYamlParser;
import com.aether.gateway.observability.JdbcRequestLogQueryRepository;
import com.aether.gateway.observability.JdbcRequestLogWriter;
import com.aether.gateway.observability.RequestLogPartitionMaintainer;
import com.aether.gateway.observability.JdbcUsageQueryRepository;
import com.aether.gateway.observability.MicrometerMetricsAdapter;
import com.aether.gateway.providers.gemini.GeminiAdapter;
import com.aether.gateway.providers.mock.MockProviderAdapter;
import com.aether.gateway.providers.ollama.OllamaAdapter;
import com.aether.gateway.providers.openai.OpenAiCompatibleAdapter;
import com.aether.gateway.quota.JdbcApiKeyAdminRepository;
import com.aether.gateway.quota.JdbcApiKeyRepository;
import com.aether.gateway.quota.RedisConcurrencyCap;
import com.aether.gateway.quota.RedisMonthlyBudget;
import com.aether.gateway.quota.RedisQuotaAdapter;
import com.aether.gateway.quota.RedisIpRateLimitAdapter;
import com.aether.gateway.quota.RedisTokenBucket;
import com.aether.gateway.registry.JdbcPromptRegistry;
import com.aether.gateway.router.RouterBreakerStatus;
import com.aether.gateway.router.RouterModelCatalog;
import com.aether.gateway.router.resilience.BreakerHintGateway;
import com.aether.gateway.router.resilience.RedisBreakerHintGateway;
import com.aether.gateway.router.resilience.ResilientRouter;
import com.aether.gateway.router.routing.RoutingPolicyRepository;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

@Configuration
public class GatewayConfig {

    @Bean
    public Scheduler virtualThreadScheduler() {
        return Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        // Modest defaults suited to the mock provider's fast failure
        // injection: few calls needed before the breaker has enough
        // signal to open (ADR-004's local-state breaker, per (provider,
        // model) per F3.1).
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(50)
                .build();
        return BulkheadRegistry.of(config);
    }

    @Bean
    public RetryBackoff retryBackoff() {
        // Base kept small deliberately: AC6 / the M2 exit criterion
        // requires failover to a healthy fallback within 500ms total,
        // and every retry against an already-failing provider eats into
        // that budget before the fallback is even tried.
        return new RetryBackoff(30, 2000);
    }

    @Bean
    public BreakerHintGateway breakerHintGateway(StringRedisTemplate redisTemplate) {
        return new RedisBreakerHintGateway(redisTemplate);
    }

    @Bean
    public RoutingPolicyRepository routingPolicyRepository(
            @Value("${aether.routing.config-path}") String routingConfigPath) {
        return new RoutingPolicyRepository(Path.of(routingConfigPath), this::buildProviderAdapter);
    }

    /**
     * F2.2 (Phase 12/M9): dispatches on routing.yaml's per-provider
     * {@code type} to the matching {@link ProviderAdapter} implementation.
     * "mock" is the default (backward-compatible with every routing.yaml
     * written before this phase). The real credential, if any, is
     * resolved from the environment here - the one place in this call
     * chain allowed to read it - never logged, never passed through
     * anything that serialises config back out (F9.2).
     */
    private ProviderAdapter buildProviderAdapter(com.aether.gateway.router.routing.ProviderConfig providerConfig) {
        String apiKey = providerConfig.apiKeyEnvVar() != null ? System.getenv(providerConfig.apiKeyEnvVar()) : null;
        return switch (providerConfig.type()) {
            case "ollama" -> new OllamaAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            case "gemini" -> new GeminiAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            case "groq", "openai-compatible" -> new OpenAiCompatibleAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            default -> new MockProviderAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), RestClient.builder(), WebClient.builder());
        };
    }

    @Bean
    public ResilientRouter resilientRouter(
            RoutingPolicyRepository routingPolicyRepository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryBackoff retryBackoff,
            BreakerHintGateway breakerHintGateway) {
        return new ResilientRouter(
                routingPolicyRepository,
                circuitBreakerRegistry,
                bulkheadRegistry,
                retryBackoff,
                1,
                Duration.ofSeconds(10),
                breakerHintGateway);
    }

    @Bean
    public ChatCompletionUseCase chatCompletionUseCase(ResilientRouter resilientRouter) {
        return resilientRouter;
    }

    @Bean
    public ChatStreamUseCase chatStreamUseCase(ResilientRouter resilientRouter) {
        return resilientRouter;
    }

    @Bean
    public ModelCatalogUseCase modelCatalogUseCase(RoutingPolicyRepository routingPolicyRepository) {
        return new RouterModelCatalog(routingPolicyRepository);
    }

    @Bean
    public BreakerStatusUseCase breakerStatusUseCase(
            RoutingPolicyRepository routingPolicyRepository, CircuitBreakerRegistry circuitBreakerRegistry) {
        return new RouterBreakerStatus(routingPolicyRepository, circuitBreakerRegistry);
    }

    // No separate @Bean for RoutingReloadUseCase: RoutingPolicyRepository
    // itself implements it, and the routingPolicyRepository bean above
    // already satisfies that type for autowiring. A second @Bean method
    // returning the same instance creates two candidate beans of the
    // same type and breaks single-bean autowiring (RoutesController).

    // Phase 06 (M3): F5 quota enforcement. gateway-router/gateway-proxy
    // only ever see QuotaPort/ApiKeyLookupPort (ADR-009's hexagonal
    // boundary); gateway-quota's Redis/JDBC specifics stay behind them.
    @Bean
    public ApiKeyLookupPort apiKeyLookupPort(JdbcClient jdbcClient) {
        return new JdbcApiKeyRepository(jdbcClient);
    }

    @Bean
    public ApiKeyAdminPort apiKeyAdminPort(JdbcClient jdbcClient) {
        return new JdbcApiKeyAdminRepository(jdbcClient);
    }

    @Bean
    public QuotaPort quotaPort(StringRedisTemplate redisTemplate) {
        return new RedisQuotaAdapter(
                new RedisTokenBucket(redisTemplate),
                new RedisConcurrencyCap(redisTemplate),
                new RedisMonthlyBudget(redisTemplate));
    }

    @Bean
    public IpRateLimitPort ipRateLimitPort(
            StringRedisTemplate redisTemplate,
            @Value("${aether.security.anonymous-ip-rps-limit:5}") int anonymousIpRpsLimit) {
        return new RedisIpRateLimitAdapter(redisTemplate, anonymousIpRpsLimit);
    }

    @Bean
    public TokenEstimator tokenEstimator(
            @Value("${aether.quota.assumed-max-output-tokens:500}") long assumedMaxOutputTokens) {
        return new TokenEstimator(assumedMaxOutputTokens);
    }

    // Phase 07 (M4): F4 semantic cache. ADR-007: Spring AI usage is
    // scoped to exactly the embedding model; the vector store and Redis
    // exact-match layer are hand-built. warmUp() runs at startup
    // deliberately, so the one-time ONNX model download/load happens
    // during application boot rather than stalling the first real
    // request.
    @Bean
    public EmbeddingGenerator embeddingGenerator(
            @Value("${aether.cache.onnx-resource-cache-dir:/tmp/aether-onnx-cache}") String resourceCacheDirectory) {
        EmbeddingGenerator generator = new EmbeddingGenerator(resourceCacheDirectory);
        generator.warmUp();
        return generator;
    }

    @Bean
    public CachePort cachePort(
            StringRedisTemplate redisTemplate,
            JdbcClient jdbcClient,
            EmbeddingGenerator embeddingGenerator,
            @Value("${aether.cache.default-threshold:0.94}") double defaultThreshold) {
        return new CacheAdapter(
                new RedisExactMatchStore(redisTemplate),
                new PgVectorCacheStore(jdbcClient),
                embeddingGenerator,
                defaultThreshold);
    }

    // Phase 08 (M5): F6.1-F6.4 observability. MeterRegistry is
    // auto-configured by spring-boot-starter-actuator +
    // micrometer-registry-prometheus (exposed at /actuator/prometheus);
    // JdbcRequestLogWriter owns its own background thread so RequestLogPort.log
    // never blocks the caller (F6.2's core correctness requirement).
    @Bean
    public MetricsPort metricsPort(MeterRegistry meterRegistry) {
        return new MicrometerMetricsAdapter(meterRegistry);
    }

    @Bean(destroyMethod = "shutdown")
    public JdbcRequestLogWriter jdbcRequestLogWriter(JdbcClient jdbcClient) {
        return new JdbcRequestLogWriter(jdbcClient);
    }

    @Bean
    public RequestLogPort requestLogPort(JdbcRequestLogWriter jdbcRequestLogWriter) {
        return jdbcRequestLogWriter;
    }

    // request_log is RANGE-partitioned with no DEFAULT partition, so
    // writes stop dead the moment the calendar runs past the last
    // partition. The maintainer keeps a rolling window open ahead of
    // now; see RequestLogPartitionScheduler for when it runs.
    @Bean
    public RequestLogPartitionMaintainer requestLogPartitionMaintainer(
            JdbcClient jdbcClient,
            @Value("${aether.request-log.partition-months-ahead}") int partitionMonthsAhead) {
        return new RequestLogPartitionMaintainer(jdbcClient, partitionMonthsAhead);
    }

    @Bean
    public UsageQueryPort usageQueryPort(JdbcClient jdbcClient) {
        return new JdbcUsageQueryRepository(jdbcClient);
    }

    @Bean
    public RequestLogQueryPort requestLogQueryPort(JdbcClient jdbcClient) {
        return new JdbcRequestLogQueryRepository(jdbcClient);
    }

    @Bean
    public CostModelRepository costModelRepository(@Value("${aether.cost-model.config-path}") String costModelConfigPath) {
        return new CostModelRepository(Path.of(costModelConfigPath), new CostModelYamlParser());
    }

    // Phase 10 (M7): the React operator console (PRD 9.4) runs on its
    // own origin (Vite dev server, typically localhost:5173) and calls
    // the admin API directly from the browser - real cross-origin
    // requests, which the browser blocks without an explicit CORS
    // grant. Scoped to /admin/** only, the same boundary AdminAuthFilter
    // enforces; the gateway's own /v1/* API is server-to-server and
    // needs no such grant.
    @Bean
    public CorsWebFilter corsWebFilter(@Value("${aether.admin.console-origin:http://localhost:5173}") String consoleOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(consoleOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-Aether-Admin-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/admin/**", config);
        return new CorsWebFilter(source);
    }

    // Phase 10 (M7): F7.1-F7.4 prompt registry. gateway-router/gateway-proxy
    // only ever see PromptRegistryPort; gateway-registry's JDBC specifics
    // stay behind it, same boundary discipline as QuotaPort/CachePort above.
    @Bean
    public PromptRegistryPort promptRegistryPort(JdbcClient jdbcClient) {
        return new JdbcPromptRegistry(jdbcClient);
    }

    @Bean
    public CostModelPort costModelPort(CostModelRepository costModelRepository) {
        return costModelRepository;
    }
}
