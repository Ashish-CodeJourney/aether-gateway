package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
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

class ChatCompletionControllerStreamingErrorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatCompletionUseCase chatCompletionUseCase = mock(ChatCompletionUseCase.class);
    private final ChatStreamUseCase chatStreamUseCase = mock(ChatStreamUseCase.class);
    private final IpRateLimitPort ipRateLimitPort = mock(IpRateLimitPort.class);
    private final CachePort cachePort = mock(CachePort.class);
    private final RoutingSource routingSource = mock(RoutingSource.class);
    private final MetricsPort metricsPort = mock(MetricsPort.class);
    private final PromptRegistryPort promptRegistryPort = mock(PromptRegistryPort.class);

    private WebTestClient anonymousClient() {
        when(ipRateLimitPort.tryConsume(anyString())).thenReturn(true);
        when(routingSource.routeFor(anyString())).thenReturn(Optional.of(new RouteConfig(
                "mock", List.of(new ChainMember("mock-primary", "mock", 100)),
                new RouteCacheConfig(true, OptionalDouble.of(0.95), Duration.ofMinutes(5), true))));
        when(cachePort.lookup(anyString(), any(), any(), anyBoolean())).thenReturn(new CacheDecision.Miss());
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
                mock(CostModelPort.class),
                promptRegistryPort,
                new ChatCompletionDtoMapper(),
                Schedulers.immediate(),
                true,
                0.94);
        return WebTestClient.bindToController(controller).build();
    }

    private static ChatCompletionRequestDto streamingRequest() {
        return new ChatCompletionRequestDto("mock", List.of(new ChatMessageDto("user", "hi")), true, null, null, null);
    }

    @Test
    void answersWithTheProvidersRealStatusWhenTheStreamFailsBeforeItsFirstChunk() {
        var failing = reactor.core.publisher.Flux.<ProviderResponse.StreamChunk>error(
                new ProviderStreamException(400, "provider_error", "400 Bad Request from POST https://api.anthropic.com/v1/messages", false));
        when(chatStreamUseCase.stream(any())).thenReturn(reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher(failing));

        anonymousClient().post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(streamingRequest())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("provider_error")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(detail -> assertThat(detail.toString()).contains("api.anthropic.com"));
    }

    @Test
    void endsTheStreamWithAnOpenAiStyleErrorEventWhenTheProviderFailsMidStream() {
        var chunks = reactor.core.publisher.Flux.just(new ProviderResponse.StreamChunk("s", 0, "Hel", false, List.of(), null))
                .concatWith(reactor.core.publisher.Flux.error(
                        new ProviderStreamException(503, "provider_error", "upstream overloaded", true)));
        when(chatStreamUseCase.stream(any())).thenReturn(reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher(chunks));

        String body = anonymousClient().post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(streamingRequest())
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(lines -> String.join("\n", lines))
                .block(Duration.ofSeconds(5));

        assertThat(body).contains("Hel");
        assertThat(body).contains("\"error\":{\"message\":\"upstream overloaded\",\"type\":\"provider_error\",\"code\":503}");
        assertThat(body).doesNotContain("[DONE]");
    }
}
