package com.aether.gateway.proxy.config;

import com.aether.gateway.cache.inmemory.InMemoryExactMatchCache;
import com.aether.gateway.observability.inmemory.InMemoryRequestLog;
import com.aether.gateway.quota.inmemory.InMemoryApiKeyStore;
import com.aether.gateway.quota.inmemory.InMemoryIpRateLimitAdapter;
import com.aether.gateway.quota.inmemory.InMemoryQuotaAdapter;
import com.aether.gateway.registry.inmemory.InMemoryPromptRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.InstantSource;

/**
 * ADR-010 lite mode ({@code SPRING_PROFILES_ACTIVE=lite}): every
 * infrastructure port served from process memory, so the gateway runs
 * as one container with no Postgres, Redis or embedding model. One
 * replica only, and nothing survives a restart - see
 * {@link LiteModeStartupReport}. There is deliberately no
 * {@code BreakerHintGateway} here: with a single replica there is no
 * other replica to share breaker state with (ADR-004).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aether.mode", havingValue = "lite")
public class LiteModeInfrastructureConfig {

    @Bean
    public InstantSource liteModeClock() {
        return InstantSource.system();
    }

    /** One store behind both {@code ApiKeyLookupPort} and {@code ApiKeyAdminPort}. */
    @Bean
    public InMemoryApiKeyStore inMemoryApiKeyStore(
            InstantSource liteModeClock,
            @Value("${aether.lite.bootstrap-api-key:}") String bootstrapApiKey) {
        InMemoryApiKeyStore store = new InMemoryApiKeyStore(liteModeClock);
        if (!bootstrapApiKey.isBlank()) {
            store.registerBootstrapKey("bootstrap (AETHER_LITE_BOOTSTRAP_API_KEY)", bootstrapApiKey);
        }
        return store;
    }

    @Bean
    public InMemoryQuotaAdapter inMemoryQuotaAdapter(InstantSource liteModeClock) {
        return new InMemoryQuotaAdapter(liteModeClock);
    }

    @Bean
    public InMemoryIpRateLimitAdapter inMemoryIpRateLimitAdapter(
            InstantSource liteModeClock,
            @Value("${aether.security.anonymous-ip-rps-limit:5}") int anonymousIpRpsLimit,
            @Value("${aether.lite.max-tracked-ips:100000}") int maxTrackedIps) {
        return new InMemoryIpRateLimitAdapter(anonymousIpRpsLimit, maxTrackedIps, liteModeClock);
    }

    @Bean
    public InMemoryExactMatchCache inMemoryExactMatchCache(
            InstantSource liteModeClock,
            @Value("${aether.lite.cache.max-entries:10000}") int maxEntries,
            @Value("${aether.lite.cache.max-bytes:67108864}") long maxBytes) {
        return new InMemoryExactMatchCache(maxEntries, maxBytes, liteModeClock);
    }

    @Bean
    public InMemoryPromptRegistry inMemoryPromptRegistry(InstantSource liteModeClock) {
        return new InMemoryPromptRegistry(liteModeClock);
    }

    /** One ring buffer behind {@code RequestLogPort}, {@code RequestLogQueryPort} and {@code UsageQueryPort}. */
    @Bean
    public InMemoryRequestLog inMemoryRequestLog(@Value("${aether.lite.request-log.capacity:10000}") int capacity) {
        return new InMemoryRequestLog(capacity);
    }

    @Bean
    public LiteModeStartupReport liteModeStartupReport(Environment environment) {
        return new LiteModeStartupReport(environment, System.getenv());
    }
}
