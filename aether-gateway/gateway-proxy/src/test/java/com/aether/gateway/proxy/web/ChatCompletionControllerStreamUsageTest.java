package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ModelPricing;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.domain.RequestLogEntry;
import com.aether.gateway.core.domain.RequestMetrics;
import com.aether.gateway.core.domain.RouteCacheConfig;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.TokenEstimator;
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
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #2: a streamed reply is billed, logged and reconciled against the
 * usage the provider reports on the stream's last chunk, and falls back
 * to the pre-request estimate only when the provider reported none.
 */
class ChatCompletionControllerStreamUsageTest {

    private static final String KEY_ID = "3f0e6c1e-8a43-4c55-9d0e-0b6f1a2b3c4d";
    private static final long ESTIMATED_TOKENS = 50;

    private final ChatStreamUseCase chatStreamUseCase = mock(ChatStreamUseCase.class);
    private final ApiKeyLookupPort apiKeyLookupPort = mock(ApiKeyLookupPort.class);
    private final QuotaPort quotaPort = mock(QuotaPort.class);
    private final TokenEstimator tokenEstimator = mock(TokenEstimator.class);
    private final CachePort cachePort = mock(CachePort.class);
    private final RoutingSource routingSource = mock(RoutingSource.class);
    private final MetricsPort metricsPort = mock(MetricsPort.class);
    private final RequestLogPort requestLogPort = mock(RequestLogPort.class);
    private final CostModelPort costModelPort = mock(CostModelPort.class);

    private WebTestClient authenticatedClientOnClaudeRoute(CacheDecision cacheDecision) {
        var key = new ApiKeyContext(KEY_ID, 10, 5, 10_000_000L);
        when(apiKeyLookupPort.findByRawKey("aeth_real")).thenReturn(Optional.of(key));
        when(tokenEstimator.estimatePessimisticTotal(any())).thenReturn(ESTIMATED_TOKENS);
        when(quotaPort.checkAndReserve(eq(key), anyString(), anyLong())).thenReturn(new QuotaDecision.Allowed("req-1", 9_000_000, false));
        when(routingSource.routeFor("claude")).thenReturn(Optional.of(new RouteConfig(
                "claude", List.of(new ChainMember("anthropic-main", "claude-haiku-5-5", 100)),
                new RouteCacheConfig(true, OptionalDouble.empty(), Duration.ofMinutes(5), false))));
        when(cachePort.lookup(anyString(), any(), any(), anyBoolean())).thenReturn(cacheDecision);
        when(costModelPort.pricingFor(anyString(), anyString())).thenReturn(Optional.empty());
        when(costModelPort.pricingFor("anthropic-main", "claude-haiku-5-5")).thenReturn(Optional.of(new ModelPricing(
                "anthropic-main", "claude-haiku-5-5", new BigDecimal("0.10"), new BigDecimal("0.50"))));
        var controller = new ChatCompletionController(
                mock(ChatCompletionUseCase.class),
                chatStreamUseCase,
                apiKeyLookupPort,
                quotaPort,
                mock(IpRateLimitPort.class),
                tokenEstimator,
                cachePort,
                routingSource,
                metricsPort,
                requestLogPort,
                costModelPort,
                mock(PromptRegistryPort.class),
                new ChatCompletionDtoMapper(),
                Schedulers.immediate(),
                false,
                0.94);
        return WebTestClient.bindToController(controller).build();
    }

    private void providerStreams(ProviderResponse.StreamChunk... chunks) {
        when(chatStreamUseCase.stream(any())).thenReturn(JdkFlowAdapter.publisherToFlowPublisher(Flux.just(chunks)));
    }

    private List<String> streamClaude() {
        return streamClaude(new CacheDecision.Bypass("no-cache"));
    }

    private List<String> streamClaude(CacheDecision cacheDecision) {
        return authenticatedClientOnClaudeRoute(cacheDecision).post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer aeth_real")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatCompletionRequestDto("claude", List.of(new ChatMessageDto("user", "hi")), true, null, null, null))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private RequestLogEntry loggedRequest() {
        var entry = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogPort, timeout(1000)).log(entry.capture());
        return entry.getValue();
    }

    private RequestMetrics recordedMetrics() {
        var metrics = ArgumentCaptor.forClass(RequestMetrics.class);
        verify(metricsPort, timeout(1000)).recordRequest(metrics.capture());
        return metrics.getValue();
    }

    @Test
    void billsAndLogsAStreamAtTheUsageTheProviderReported() {
        providerStreams(
                new ProviderResponse.StreamChunk("s", 0, "Hi", false),
                new ProviderResponse.StreamChunk("s", 1, "", true, List.of(), "stop", Usage.of(1_000_000, 1_000_000)));

        streamClaude();

        var entry = loggedRequest();
        assertThat(entry.inputTokens()).isEqualTo(1_000_000);
        assertThat(entry.outputTokens()).isEqualTo(1_000_000);
        assertThat(entry.costUsd()).isEqualByComparingTo("0.60");
        assertThat(entry.provider()).isEqualTo("anthropic-main");
        assertThat(entry.model()).isEqualTo("claude-haiku-5-5");
        assertThat(entry.status()).isEqualTo("OK");

        var metrics = recordedMetrics();
        assertThat(metrics.inputTokens()).isEqualTo(1_000_000);
        assertThat(metrics.outputTokens()).isEqualTo(1_000_000);
        assertThat(metrics.costUsd()).isEqualTo(0.60);
    }

    @Test
    void reconcilesTheQuotaReservationToTheReportedTotalTokens() {
        providerStreams(
                new ProviderResponse.StreamChunk("s", 0, "Hi", false),
                new ProviderResponse.StreamChunk("s", 1, "", true, List.of(), "stop", Usage.of(30, 12)));

        streamClaude();

        verify(quotaPort, timeout(1000)).reconcile(KEY_ID, "req-1", 42);
        verify(quotaPort, timeout(1000)).releaseConcurrencySlot(KEY_ID, "req-1");
    }

    @Test
    void fallsBackToThePreRequestEstimateWhenTheProviderReportsNoUsage() {
        providerStreams(
                new ProviderResponse.StreamChunk("s", 0, "Hi", false),
                new ProviderResponse.StreamChunk("s", 1, "", true, List.of(), "stop"));

        streamClaude();

        verify(quotaPort, timeout(1000)).reconcile(KEY_ID, "req-1", ESTIMATED_TOKENS);
        var entry = loggedRequest();
        assertThat(entry.inputTokens()).isZero();
        assertThat(entry.outputTokens()).isEqualTo((int) ESTIMATED_TOKENS);
    }

    @Test
    void settlesACancelledStreamAtTheEstimateSinceItsUsageNeverArrived() {
        when(chatStreamUseCase.stream(any())).thenReturn(JdkFlowAdapter.publisherToFlowPublisher(
                Flux.just(new ProviderResponse.StreamChunk("s", 0, "Hi", false)).concatWith(Flux.never())));

        authenticatedClientOnClaudeRoute(new CacheDecision.Bypass("no-cache")).post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer aeth_real")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChatCompletionRequestDto("claude", List.of(new ChatMessageDto("user", "hi")), true, null, null, null))
                .exchange()
                .returnResult(String.class)
                .getResponseBody()
                .take(1)
                .blockLast(Duration.ofSeconds(5));

        verify(quotaPort, timeout(1000)).reconcile(KEY_ID, "req-1", ESTIMATED_TOKENS);
        var entry = loggedRequest();
        assertThat(entry.status()).isEqualTo("CANCELLED");
        assertThat(entry.outputTokens()).isEqualTo((int) ESTIMATED_TOKENS);
    }

    @Test
    void cachesAStreamedReplyWithItsReportedUsageSoALaterHitIsValuedAtIt() {
        providerStreams(
                new ProviderResponse.StreamChunk("s", 0, "Hi", false),
                new ProviderResponse.StreamChunk("s", 1, "", true, List.of(), "stop", Usage.of(30, 12)));

        streamClaude(new CacheDecision.Miss());

        var storedJson = ArgumentCaptor.forClass(String.class);
        verify(cachePort, timeout(1000)).store(anyString(), any(), storedJson.capture(), any(), any());
        var stored = new ChatCompletionDtoMapper().responseFromJson(storedJson.getValue());
        assertThat(stored.choices().get(0).message().content()).isEqualTo("Hi");
        assertThat(stored.usage()).isEqualTo(new UsageDto(30, 12, 42));
    }
}
