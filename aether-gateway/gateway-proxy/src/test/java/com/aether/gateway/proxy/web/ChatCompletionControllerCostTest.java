package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ModelPricing;
import com.aether.gateway.core.domain.RequestMetrics;
import java.math.BigDecimal;
import com.aether.gateway.core.domain.RouteCacheConfig;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.TokenEstimator;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolsNotSupportedException;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.CachePort;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.CostModelPort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.MetricsPort;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.core.port.QuotaPort;
import com.aether.gateway.core.port.RequestLogPort;
import com.aether.gateway.router.routing.RoutingSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCompletionControllerCostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatCompletionUseCase chatCompletionUseCase = mock(ChatCompletionUseCase.class);
    private final ChatStreamUseCase chatStreamUseCase = mock(ChatStreamUseCase.class);
    private final IpRateLimitPort ipRateLimitPort = mock(IpRateLimitPort.class);
    private final CachePort cachePort = mock(CachePort.class);
    private final RoutingSource routingSource = mock(RoutingSource.class);
    private final MetricsPort metricsPort = mock(MetricsPort.class);
    private final PromptRegistryPort promptRegistryPort = mock(PromptRegistryPort.class);
    private final CostModelPort costModelPort = mock(CostModelPort.class);

    private WebTestClient clientWithClaudeRoute(CacheDecision cacheDecision) {
        when(ipRateLimitPort.tryConsume(anyString())).thenReturn(true);
        when(routingSource.routeFor("claude")).thenReturn(Optional.of(new RouteConfig(
                "claude", List.of(new ChainMember("anthropic-main", "claude-haiku-5-5", 100)),
                new RouteCacheConfig(true, java.util.OptionalDouble.empty(), Duration.ofMinutes(5), false))));
        when(cachePort.lookup(anyString(), any(), any(), anyBoolean())).thenReturn(cacheDecision);
        when(costModelPort.pricingFor(anyString(), anyString())).thenReturn(Optional.empty());
        when(costModelPort.pricingFor("anthropic-main", "claude-haiku-5-5")).thenReturn(Optional.of(new ModelPricing(
                "anthropic-main", "claude-haiku-5-5", new BigDecimal("0.10"), new BigDecimal("0.50"))));
        var controller = new ChatCompletionController(
                chatCompletionUseCase,
                chatStreamUseCase,
                mock(ApiKeyLookupPort.class),
                mock(QuotaPort.class),
                ipRateLimitPort,
                mock(TokenEstimator.class),
                cachePort,
                routingSource,
                metricsPort,
                mock(RequestLogPort.class),
                costModelPort,
                promptRegistryPort,
                new ChatCompletionDtoMapper(),
                Schedulers.immediate(),
                true,
                0.94);
        return WebTestClient.bindToController(controller).build();
    }

    private static ChatCompletionResponse claudeResponse() {
        return new ChatCompletionResponse("msg_1", "chat.completion", 1L, "claude-haiku-5-5",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi"), "stop")),
                Usage.of(1_000_000, 1_000_000));
    }

    private static ChatCompletionRequestDto claudeRequest() {
        return new ChatCompletionRequestDto("claude", List.of(new ChatMessageDto("user", "hi")), false, null, null, null);
    }

    @Test
    void pricesARouteAliasByTheUpstreamModelTheServingProviderRan() {
        when(chatCompletionUseCase.complete(any())).thenReturn(
                new ProviderResponse.Completion(claudeResponse(), "anthropic-main", 1, List.of("anthropic-main")));

        clientWithClaudeRoute(new CacheDecision.Miss()).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(claudeRequest())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Cost-USD", "0.60");

        var metrics = ArgumentCaptor.forClass(RequestMetrics.class);
        verify(metricsPort).recordRequest(metrics.capture());
        assertThat(metrics.getValue().model()).isEqualTo("claude-haiku-5-5");
        assertThat(metrics.getValue().routeAlias()).isEqualTo("claude");
    }

    @Test
    void valuesACacheHitOnARouteAliasAtTheRoutesPrimaryUpstreamModelPrice() {
        String cachedBody = new ChatCompletionDtoMapper().toJson(new ChatCompletionDtoMapper().toDto(claudeResponse()));

        clientWithClaudeRoute(new CacheDecision.ExactHit("entry-1", cachedBody)).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(claudeRequest())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Aether-Cost-USD", "0");

        var metrics = ArgumentCaptor.forClass(RequestMetrics.class);
        verify(metricsPort).recordRequest(metrics.capture());
        assertThat(metrics.getValue().savedUsd()).isEqualTo(0.60);
    }
}
