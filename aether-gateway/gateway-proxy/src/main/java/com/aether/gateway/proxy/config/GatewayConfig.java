package com.aether.gateway.proxy.config;

import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.EmbeddingUseCase;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.observability.CostModelRepository;
import com.aether.gateway.observability.CostModelYamlParser;
import com.aether.gateway.providers.anthropic.AnthropicAdapter;
import com.aether.gateway.providers.echo.EchoProviderAdapter;
import com.aether.gateway.observability.MicrometerMetricsAdapter;
import com.aether.gateway.providers.gemini.GeminiAdapter;
import com.aether.gateway.providers.mock.MockProviderAdapter;
import com.aether.gateway.providers.ollama.OllamaAdapter;
import com.aether.gateway.providers.openai.OpenAiCompatibleAdapter;
import com.aether.gateway.router.RouterBreakerStatus;
import com.aether.gateway.router.RouterModelCatalog;
import com.aether.gateway.router.resilience.BreakerHintGateway;
import com.aether.gateway.router.resilience.ResilientEmbeddingRouter;
import com.aether.gateway.router.resilience.ResilientRouter;
import com.aether.gateway.router.routing.RoutingPolicyRepository;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Wiring shared by both infrastructure modes (ADR-010): routing,
 * resilience, provider adapters, metrics, cost model and CORS. Which
 * stores sit behind the quota, cache, key, prompt and request-log ports
 * is decided by {@link FullModeInfrastructureConfig} or
 * {@link LiteModeInfrastructureConfig}.
 */
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

    // Anthropic's Messages API requires max_tokens on every request and
    // the domain ChatCompletionRequest has nowhere to carry a
    // caller-supplied one, so the adapter needs a configured default.
    @Value("${aether.providers.anthropic.default-max-tokens:4096}")
    private int anthropicDefaultMaxTokens;

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
            case "echo" -> new EchoProviderAdapter(providerConfig.name());
            case "ollama" -> new OllamaAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            case "gemini" -> new GeminiAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            case "anthropic" -> new AnthropicAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, anthropicDefaultMaxTokens,
                    RestClient.builder(), WebClient.builder());
            // "openai" is spelled out alongside the generic type so a
            // routing.yaml can name it directly; OpenAI itself speaks
            // exactly this wire format, as do Groq, LiteLLM, vLLM and
            // anything else fronting an OpenAI-compatible endpoint.
            case "groq", "openai", "openai-compatible" -> new OpenAiCompatibleAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), apiKey, RestClient.builder(), WebClient.builder());
            default -> new MockProviderAdapter(
                    providerConfig.name(), providerConfig.baseUrl(), RestClient.builder(), WebClient.builder());
        };
    }

    // ADR-004 / ADR-010: the cross-replica breaker hint only exists in
    // full mode; with a single lite replica there is nobody to share
    // breaker state with, and ResilientRouter treats null as "no hints".
    @Bean
    public ResilientRouter resilientRouter(
            RoutingPolicyRepository routingPolicyRepository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryBackoff retryBackoff,
            ObjectProvider<BreakerHintGateway> breakerHintGateway) {
        return new ResilientRouter(
                routingPolicyRepository,
                circuitBreakerRegistry,
                bulkheadRegistry,
                retryBackoff,
                1,
                Duration.ofSeconds(10),
                breakerHintGateway.getIfAvailable());
    }

    @Bean
    public ChatCompletionUseCase chatCompletionUseCase(ResilientRouter resilientRouter) {
        return resilientRouter;
    }

    @Bean
    public ChatStreamUseCase chatStreamUseCase(ResilientRouter resilientRouter) {
        return resilientRouter;
    }

    // Same registries and limits as the chat router, so a provider's
    // breaker state and bulkhead capacity are shared across endpoints.
    @Bean
    public EmbeddingUseCase embeddingUseCase(
            RoutingPolicyRepository routingPolicyRepository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryBackoff retryBackoff,
            ObjectProvider<BreakerHintGateway> breakerHintGateway) {
        return new ResilientEmbeddingRouter(
                routingPolicyRepository,
                circuitBreakerRegistry,
                bulkheadRegistry,
                retryBackoff,
                1,
                Duration.ofSeconds(10),
                breakerHintGateway.getIfAvailable());
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

    @Bean
    public TokenEstimator tokenEstimator(
            @Value("${aether.quota.assumed-max-output-tokens:500}") long assumedMaxOutputTokens) {
        return new TokenEstimator(assumedMaxOutputTokens);
    }

    // Phase 08 (M5): F6.1-F6.4 observability. MeterRegistry is
    // auto-configured by spring-boot-starter-actuator +
    // micrometer-registry-prometheus (exposed at /actuator/prometheus).
    @Bean
    public MetricsPort metricsPort(MeterRegistry meterRegistry) {
        return new MicrometerMetricsAdapter(meterRegistry);
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

    /** ADR-010: {@code GET /actuator/info} says which infrastructure mode this process is running in. */
    @Bean
    public InfoContributor aetherModeInfoContributor(@Value("${aether.mode:full}") String mode) {
        return builder -> builder.withDetail("aether", Map.of("mode", mode));
    }

    @Bean
    public CostModelPort costModelPort(CostModelRepository costModelRepository) {
        return costModelRepository;
    }
}
