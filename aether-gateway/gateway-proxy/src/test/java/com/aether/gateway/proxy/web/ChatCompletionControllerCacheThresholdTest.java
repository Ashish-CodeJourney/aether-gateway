package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.CacheMatching;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a cached route matches. Threshold precedence: a valid
 * {@code X-Aether-Cache-Threshold} header first, then the route's own
 * {@code cache.threshold} from routing.yaml, then the global
 * {@code aether.cache.default-threshold}. A route with
 * {@code cache.semantic: false} matches exactly only.
 */
class ChatCompletionControllerCacheThresholdTest {

    private static final double GLOBAL_DEFAULT_THRESHOLD = 0.94;
    private static final Duration TTL = Duration.ofHours(1);
    private static final String CACHED_BODY = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"mock","choices":[]}""";

    private final CachePort cachePort = mock(CachePort.class);
    private final RoutingSource routingSource = mock(RoutingSource.class);
    private final IpRateLimitPort ipRateLimitPort = mock(IpRateLimitPort.class);
    private final ChatCompletionUseCase chatCompletionUseCase = mock(ChatCompletionUseCase.class);

    private WebTestClient client(CacheDecision cacheDecision) {
        when(ipRateLimitPort.tryConsume(anyString())).thenReturn(true);
        when(cachePort.lookup(anyString(), any(), any(), anyBoolean())).thenReturn(cacheDecision);
        var controller = new ChatCompletionController(
                chatCompletionUseCase,
                mock(ChatStreamUseCase.class),
                mock(ApiKeyLookupPort.class),
                mock(QuotaPort.class),
                ipRateLimitPort,
                mock(TokenEstimator.class),
                cachePort,
                routingSource,
                mock(MetricsPort.class),
                mock(RequestLogPort.class),
                mock(CostModelPort.class),
                mock(PromptRegistryPort.class),
                new ChatCompletionDtoMapper(),
                Schedulers.immediate(),
                true,
                GLOBAL_DEFAULT_THRESHOLD);
        return WebTestClient.bindToController(controller).build();
    }

    private WebTestClient client() {
        return client(new CacheDecision.ExactHit("entry-1", CACHED_BODY));
    }

    private void routeWithCache(RouteCacheConfig cache) {
        when(routingSource.routeFor("mock")).thenReturn(Optional.of(
                new RouteConfig("mock", List.of(new ChainMember("mock-primary", "mock", 100)), cache)));
    }

    private static RouteCacheConfig semanticRoute(OptionalDouble threshold) {
        return new RouteCacheConfig(true, threshold, TTL, true);
    }

    private static RouteCacheConfig exactOnlyRoute() {
        return new RouteCacheConfig(true, OptionalDouble.of(0.8), TTL, false);
    }

    private static ChatCompletionRequestDto request() {
        return new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hello")), false, null, null, null);
    }

    private static ProviderResponse.Completion providerCompletion() {
        var response = new ChatCompletionResponse(
                "chatcmpl-2", "chat.completion", 1, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi"), "stop")),
                Usage.of(1, 1));
        return new ProviderResponse.Completion(response, "mock-primary", 1, List.of("mock-primary"));
    }

    private WebTestClient.ResponseSpec send(WebTestClient client, String thresholdHeader) {
        var spec = client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (thresholdHeader != null) {
            spec = spec.header("X-Aether-Cache-Threshold", thresholdHeader);
        }
        return spec.bodyValue(request()).exchange();
    }

    @Test
    void appliesTheRoutesOwnThresholdWhenNoHeaderIsSent() {
        routeWithCache(semanticRoute(OptionalDouble.of(0.8)));

        send(client(), null).expectStatus().isOk();

        verify(cachePort).lookup(eq("anonymous"), any(), eq(new CacheMatching.Semantic(0.8)), eq(false));
    }

    @Test
    void fallsBackToTheGlobalDefaultWhenTheRouteSetsNoThreshold() {
        routeWithCache(semanticRoute(OptionalDouble.empty()));

        send(client(), null).expectStatus().isOk();

        verify(cachePort).lookup(eq("anonymous"), any(), eq(new CacheMatching.Semantic(GLOBAL_DEFAULT_THRESHOLD)), eq(false));
    }

    @Test
    void aValidHeaderTakesPrecedenceOverTheRoutesThreshold() {
        routeWithCache(semanticRoute(OptionalDouble.of(0.8)));

        send(client(), "0.97").expectStatus().isOk();

        verify(cachePort).lookup(eq("anonymous"), any(), eq(new CacheMatching.Semantic(0.97)), eq(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "0.79", "1.5", "abc", "NaN"})
    void rejectsAHeaderThatIsInvalidOrLooserThanTheRouteBeforeAnyQuotaOrCacheWork(String header) {
        routeWithCache(semanticRoute(OptionalDouble.of(0.8)));

        send(client(), header)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("invalid_request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("X-Aether-Cache-Threshold"));

        verify(ipRateLimitPort, never()).tryConsume(anyString());
        verify(cachePort, never()).lookup(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void anExactOnlyRouteLooksUpByExactMatchOnly() {
        routeWithCache(exactOnlyRoute());

        send(client(), null).expectStatus().isOk();

        verify(cachePort).lookup(eq("anonymous"), any(), eq(new CacheMatching.ExactOnly()), eq(false));
    }

    @Test
    void anExactOnlyRouteStoresAMissForExactMatchOnly() {
        routeWithCache(exactOnlyRoute());
        when(chatCompletionUseCase.complete(any())).thenReturn(providerCompletion());

        send(client(new CacheDecision.Miss()), null).expectStatus().isOk();

        verify(cachePort).store(eq("anonymous"), any(), anyString(), eq(TTL), eq(new CacheMatching.ExactOnly()));
    }

    @Test
    void aSemanticRouteStoresAMissForSemanticMatching() {
        routeWithCache(semanticRoute(OptionalDouble.of(0.8)));
        when(chatCompletionUseCase.complete(any())).thenReturn(providerCompletion());

        send(client(new CacheDecision.Miss()), null).expectStatus().isOk();

        verify(cachePort).store(eq("anonymous"), any(), anyString(), eq(TTL), eq(new CacheMatching.Semantic(0.8)));
    }
}
