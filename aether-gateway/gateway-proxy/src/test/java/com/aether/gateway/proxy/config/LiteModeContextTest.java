package com.aether.gateway.proxy.config;

import com.aether.gateway.cache.EmbeddingGenerator;
import com.aether.gateway.cache.inmemory.InMemoryExactMatchCache;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.core.port.RequestLogQueryPort;
import com.aether.gateway.core.port.UsageQueryPort;
import com.aether.gateway.observability.inmemory.InMemoryRequestLog;
import com.aether.gateway.proxy.AetherGatewayApplication;
import com.aether.gateway.quota.inmemory.InMemoryApiKeyStore;
import com.aether.gateway.quota.inmemory.InMemoryIpRateLimitAdapter;
import com.aether.gateway.quota.inmemory.InMemoryQuotaAdapter;
import com.aether.gateway.registry.inmemory.InMemoryPromptRegistry;
import com.aether.gateway.router.resilience.BreakerHintGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-010: with the lite profile the whole gateway starts with no
 * Postgres, no Redis and no embedding model - none of those beans
 * exists - and every infrastructure port is served from memory.
 */
@SpringBootTest(
        classes = AetherGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aether.routing.config-path=src/test/resources/lite-routing.yaml",
                "aether.cost-model.config-path=../cost-model.yaml",
        })
@ActiveProfiles("lite")
class LiteModeContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void noDatabaseRedisOrEmbeddingModelIsCreated() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcClient.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(StringRedisTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingGenerator.class)).isEmpty();
        assertThat(context.getBeansOfType(BreakerHintGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(RequestLogPartitionScheduler.class)).isEmpty();
    }

    @Test
    void everyInfrastructurePortIsServedFromMemory() {
        assertThat(context.getBean(ApiKeyLookupPort.class)).isInstanceOf(InMemoryApiKeyStore.class);
        assertThat(context.getBean(ApiKeyAdminPort.class)).isInstanceOf(InMemoryApiKeyStore.class);
        assertThat(context.getBean(QuotaPort.class)).isInstanceOf(InMemoryQuotaAdapter.class);
        assertThat(context.getBean(IpRateLimitPort.class)).isInstanceOf(InMemoryIpRateLimitAdapter.class);
        assertThat(context.getBean(CachePort.class)).isInstanceOf(InMemoryExactMatchCache.class);
        assertThat(context.getBean(PromptRegistryPort.class)).isInstanceOf(InMemoryPromptRegistry.class);
        assertThat(context.getBean(RequestLogPort.class)).isInstanceOf(InMemoryRequestLog.class);
        assertThat(context.getBean(RequestLogQueryPort.class)).isInstanceOf(InMemoryRequestLog.class);
        assertThat(context.getBean(UsageQueryPort.class)).isInstanceOf(InMemoryRequestLog.class);
    }

    @Test
    void theInfoEndpointReportsLiteMode() throws Exception {
        HttpResponse<String> response = get("/actuator/info", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"mode\":\"lite\"");
    }

    @Test
    void anAdminKeyIsGeneratedWhenNoneIsConfiguredAndItOpensTheAdminApi() throws Exception {
        String generated = environment.getProperty("aether.admin.api-key");

        assertThat(generated).isNotBlank();
        assertThat(get("/admin/routes", generated).statusCode()).isEqualTo(200);
        assertThat(get("/admin/routes", "wrong").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String adminKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (adminKey != null) {
            request.header("X-Aether-Admin-Key", adminKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
