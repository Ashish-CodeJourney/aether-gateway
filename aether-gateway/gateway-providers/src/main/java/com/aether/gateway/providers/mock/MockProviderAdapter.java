package com.aether.gateway.providers.mock;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.mock.wire.WireChatCompletionRequest;
import com.aether.gateway.providers.mock.wire.WireChatCompletionResponse;
import com.aether.gateway.providers.mock.wire.WireChatMessage;
import com.aether.gateway.providers.mock.wire.WireStreamChunkResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F2.1/F2.2: the mock provider's {@link ProviderAdapter}. Multiple
 * instances are registered (e.g. "mock-primary", "mock-fallback"), each
 * pointed at a different mock-provider process/URL, which is what lets
 * routing.yaml express a real failover chain in Phase 05 without any
 * real provider credentials (PRD section 14's whole reason for existing
 * first). Real providers (Ollama, Groq, Gemini) get sibling adapters in
 * Phase 12, conforming to the same {@link ProviderAdapter} contract,
 * with zero changes to gateway-router (the hexagonal boundary's payoff).
 */
public class MockProviderAdapter implements ProviderAdapter {

    private final String providerName;
    private final RestClient restClient;
    private final WebClient webClient;

    public MockProviderAdapter(String providerName, String baseUrl, RestClient.Builder restBuilder, WebClient.Builder webBuilder) {
        this.providerName = providerName;
        var snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        this.restClient = restBuilder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(snakeCaseMapper));
                })
                .build();
        this.webClient = webBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ProviderResponse invoke(ChatCompletionRequest request) {
        var wireRequest = toWireRequest(request, false);
        try {
            WireChatCompletionResponse wireResponse = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(wireRequest)
                    .retrieve()
                    .body(WireChatCompletionResponse.class);
            return new ProviderResponse.Completion(toDomainResponse(wireResponse));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status == 503 || status == 504;
            return new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, retryable);
        } catch (RestClientException e) {
            return new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true);
        }
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
        var snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toWireRequest(request, true))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .takeWhile(sse -> !"[DONE]".equals(sse.data()))
                .map(sse -> {
                    WireStreamChunkResponse chunk = snakeCaseMapper.readValue(sse.data(), WireStreamChunkResponse.class);
                    var choice = chunk.choices().get(0);
                    boolean last = choice.finishReason() != null;
                    String content = choice.delta() != null && choice.delta().content() != null ? choice.delta().content() : "";
                    return new ProviderResponse.StreamChunk(responseId, index.getAndIncrement(), content, last);
                });

        return JdkFlowAdapter.publisherToFlowPublisher(flux);
    }

    private WireChatCompletionRequest toWireRequest(ChatCompletionRequest request, boolean stream) {
        List<WireChatMessage> messages = request.messages().stream()
                .map(m -> new WireChatMessage(m.role(), m.content()))
                .toList();
        return new WireChatCompletionRequest(request.model(), messages, stream);
    }

    private ChatCompletionResponse toDomainResponse(WireChatCompletionResponse wire) {
        List<ChatCompletionChoice> choices = wire.choices().stream()
                .map(c -> new ChatCompletionChoice(
                        c.index(),
                        new ChatMessage(c.message().role(), c.message().content()),
                        c.finishReason()))
                .toList();
        Usage usage = Usage.of(wire.usage().promptTokens(), wire.usage().completionTokens());
        return new ChatCompletionResponse(wire.id(), wire.object(), wire.created(), wire.model(), choices, usage);
    }
}
