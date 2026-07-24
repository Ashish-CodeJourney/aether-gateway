package com.aether.gateway.proxy.config;

import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.ModelCatalogUseCase;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.providers.mock.MockProviderAdapter;
import com.aether.gateway.quota.JdbcApiKeyRepository;
import com.aether.gateway.quota.RedisConcurrencyCap;
import com.aether.gateway.quota.RedisMonthlyBudget;
import com.aether.gateway.quota.RedisQuotaAdapter;
import com.aether.gateway.quota.RedisTokenBucket;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
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

    private ProviderAdapter buildProviderAdapter(com.aether.gateway.router.routing.ProviderConfig providerConfig) {
        return new MockProviderAdapter(
                providerConfig.name(), providerConfig.baseUrl(), RestClient.builder(), WebClient.builder());
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
    public QuotaPort quotaPort(StringRedisTemplate redisTemplate) {
        return new RedisQuotaAdapter(
                new RedisTokenBucket(redisTemplate),
                new RedisConcurrencyCap(redisTemplate),
                new RedisMonthlyBudget(redisTemplate));
    }

    @Bean
    public TokenEstimator tokenEstimator(
            @Value("${aether.quota.assumed-max-output-tokens:500}") long assumedMaxOutputTokens) {
        return new TokenEstimator(assumedMaxOutputTokens);
    }
}
