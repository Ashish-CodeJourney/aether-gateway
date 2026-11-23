package com.aether.gateway.proxy.config;

import com.aether.gateway.cache.CacheAdapter;
import com.aether.gateway.cache.EmbeddingGenerator;
import com.aether.gateway.cache.PgVectorCacheStore;
import com.aether.gateway.cache.RedisExactMatchStore;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.core.port.RequestLogQueryPort;
import com.aether.gateway.core.port.UsageQueryPort;
import com.aether.gateway.observability.JdbcRequestLogQueryRepository;
import com.aether.gateway.observability.JdbcRequestLogWriter;
import com.aether.gateway.observability.JdbcUsageQueryRepository;
import com.aether.gateway.observability.RequestLogPartitionMaintainer;
import com.aether.gateway.quota.JdbcApiKeyAdminRepository;
import com.aether.gateway.quota.JdbcApiKeyRepository;
import com.aether.gateway.quota.RedisConcurrencyCap;
import com.aether.gateway.quota.RedisIpRateLimitAdapter;
import com.aether.gateway.quota.RedisMonthlyBudget;
import com.aether.gateway.quota.RedisQuotaAdapter;
import com.aether.gateway.quota.RedisTokenBucket;
import com.aether.gateway.registry.JdbcPromptRegistry;
import com.aether.gateway.router.resilience.BreakerHintGateway;
import com.aether.gateway.router.resilience.RedisBreakerHintGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-010: the default, multi-replica infrastructure - Postgres for
 * keys, prompts, the request log and the cache's durable tier, Redis for
 * quotas, the hot cache tier and breaker hints, and the in-process ONNX
 * embedding model for semantic caching. Active unless
 * {@code aether.mode} says otherwise.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aether.mode", havingValue = "full", matchIfMissing = true)
public class FullModeInfrastructureConfig {

    @Bean
    public BreakerHintGateway breakerHintGateway(StringRedisTemplate redisTemplate) {
        return new RedisBreakerHintGateway(redisTemplate);
    }

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
            EmbeddingGenerator embeddingGenerator) {
        return new CacheAdapter(
                new RedisExactMatchStore(redisTemplate),
                new PgVectorCacheStore(jdbcClient),
                embeddingGenerator);
    }

    // Phase 08 (M5): JdbcRequestLogWriter owns its own background thread
    // so RequestLogPort.log never blocks the caller (F6.2's core
    // correctness requirement).
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
    public RequestLogPartitionScheduler requestLogPartitionScheduler(RequestLogPartitionMaintainer maintainer) {
        return new RequestLogPartitionScheduler(maintainer);
    }

    @Bean
    public UsageQueryPort usageQueryPort(JdbcClient jdbcClient) {
        return new JdbcUsageQueryRepository(jdbcClient);
    }

    @Bean
    public RequestLogQueryPort requestLogQueryPort(JdbcClient jdbcClient) {
        return new JdbcRequestLogQueryRepository(jdbcClient);
    }

    // Phase 10 (M7): F7.1-F7.4 prompt registry. gateway-router/gateway-proxy
    // only ever see PromptRegistryPort; gateway-registry's JDBC specifics
    // stay behind it, same boundary discipline as QuotaPort/CachePort above.
    @Bean
    public PromptRegistryPort promptRegistryPort(JdbcClient jdbcClient) {
        return new JdbcPromptRegistry(jdbcClient);
    }
}
