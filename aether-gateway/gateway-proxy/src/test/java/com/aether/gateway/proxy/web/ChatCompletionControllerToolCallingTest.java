package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.CacheDecision;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.domain.ProviderResponse;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tool calling through the controller: tool payloads survive every
 * request rewrite on the way in, tool-call responses are never written
 * to the cache, and a provider that cannot do tools is reported as a
 * client error before any stream starts.
 */
class ChatCompletionControllerToolCallingTest {

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
                new RouteCacheConfig(true, 0.95, Duration.ofMinutes(5)))));
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
                true);
        return WebTestClient.bindToController(controller).build();
    }

    private static JsonNode weatherTool() {
        return JSON.readTree("""
                {"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}
                """);
    }

    private static ChatCompletionResponse toolCallResponse() {
        var call = new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}");
        return new ChatCompletionResponse("id-1", "chat.completion", 1L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "", List.of(call), null), "tool_calls")),
                Usage.of(5, 3));
    }

    @Test
    void keepsToolsAndToolChoiceWhenAPromptTemplateReplacesTheMessages() {
        when(promptRegistryPort.findByNameAndAlias("support", "prod")).thenReturn(Optional.of(new PromptVersion(
                "p1", "support", 1, List.of(new ChatMessage("user", "hello")), Set.of(), null, Instant.now())));
        when(chatCompletionUseCase.complete(any())).thenReturn(new ProviderResponse.Completion(toolCallResponse()));
        var request = new ChatCompletionRequestDto("mock", null, false, null, List.of(weatherTool()), Map.of(), JSON.readTree("\"required\""));

        anonymousClient().post()
                .uri("/v1/chat/completions")
                .header("X-Aether-Prompt", "support@prod")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chatCompletionUseCase).complete(captor.capture());
        assertThat(captor.getValue().tools()).hasSize(1);
        assertThat(captor.getValue().toolChoice()).isEqualTo("\"required\"");
    }

    @Test
    void returnsToolCallsInOpenAiShapeAndNeverCachesThem() {
        when(chatCompletionUseCase.complete(any())).thenReturn(new ProviderResponse.Completion(toolCallResponse()));
        var request = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "weather?")), false, null, List.of(weatherTool()), null);

        anonymousClient().post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].finishReason").isEqualTo("tool_calls")
                .jsonPath("$.choices[0].message.toolCalls[0].id").isEqualTo("call_1")
                .jsonPath("$.choices[0].message.toolCalls[0].function.name").isEqualTo("get_weather")
                .jsonPath("$.choices[0].message.toolCalls[0].function.arguments").isEqualTo("{\"city\":\"Paris\"}");

        verify(cachePort, never()).store(anyString(), any(), anyString(), any());
    }

    @Test
    void relaysStreamedToolCallFragmentsAndNeverCachesTheStream() {
        var chunks = reactor.core.publisher.Flux.just(
                new ProviderResponse.StreamChunk("s", 0, "", false,
                        List.of(new com.aether.gateway.core.domain.ToolCallDelta(0, "call_1", "get_weather", "")), null),
                new ProviderResponse.StreamChunk("s", 1, "", false,
                        List.of(new com.aether.gateway.core.domain.ToolCallDelta(0, null, null, "{}")), null),
                new ProviderResponse.StreamChunk("s", 2, "", true, List.of(), "tool_calls"));
        when(chatStreamUseCase.stream(any())).thenReturn(reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher(chunks));
        var request = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "weather?")), true, null, List.of(weatherTool()), null);

        String body = anonymousClient().post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(lines -> String.join("\n", lines))
                .block(Duration.ofSeconds(5));

        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"", "\"finish_reason\":\"tool_calls\"");
        verify(cachePort, never()).store(anyString(), any(), anyString(), any());
    }

    @Test
    void reportsAProviderWithoutToolSupportAsAClientErrorBeforeAnyStreamStarts() {
        when(chatStreamUseCase.stream(any())).thenThrow(new ToolsNotSupportedException("gemini-main"));
        var request = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "weather?")), true, null, List.of(weatherTool()), null);

        anonymousClient().post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("tools_not_supported")
                .jsonPath("$.detail").value(detail -> assertThat(detail.toString()).contains("gemini-main"));

        verify(metricsPort, never()).streamStarted();
    }
}
