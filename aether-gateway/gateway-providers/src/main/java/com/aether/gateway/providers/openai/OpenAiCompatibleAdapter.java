package com.aether.gateway.providers.openai;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.openai.wire.WireChatCompletionRequest;
import com.aether.gateway.providers.openai.wire.WireChatCompletionResponse;
import com.aether.gateway.providers.openai.wire.WireChatMessage;
import com.aether.gateway.providers.openai.wire.WireStreamChunkResponse;
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
 * F2.2 (Phase 12/M9): the generic OpenAI-compatible {@link ProviderAdapter}
 * - the wire format Groq, and any provider not named explicitly, actually
 * speaks. Structurally identical to {@link com.aether.gateway.providers.mock.MockProviderAdapter}
 * (deliberately: mock-provider was always modelled on this exact shape),
 * with one real addition real providers need and the mock never did: an
 * optional {@code Authorization: Bearer <apiKey>} header (F9.2 - the key
 * is only ever held in memory and attached to outbound requests, never
 * logged). Wire DTOs are duplicated from {@code providers.mock.wire}
 * rather than shared, a deliberate choice to avoid touching that
 * package's extensively-covered existing behaviour this late; both sides
 * model the same public OpenAI wire shape and are expected to drift
 * identically, if at all.
 */
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private final String providerName;
    private final String apiKey;
    private final RestClient restClient;
    private final WebClient webClient;

    public OpenAiCompatibleAdapter(
            String providerName, String baseUrl, String apiKey, RestClient.Builder restBuilder, WebClient.Builder webBuilder) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        var snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        var restBuilderWithAuth = restBuilder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(snakeCaseMapper));
                });
        var webBuilderWithAuth = webBuilder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            restBuilderWithAuth = restBuilderWithAuth.defaultHeader("Authorization", "Bearer " + apiKey);
            webBuilderWithAuth = webBuilderWithAuth.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = restBuilderWithAuth.build();
        this.webClient = webBuilderWithAuth.build();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ProviderResponse invoke(ChatCompletionRequest request) {
        var wireRequest = toWireRequest(request, false);
        try {
            // "/chat/completions", not "/v1/chat/completions": unlike
            // mock-provider's fixed baseUrl (bare host, no version
            // prefix), real OpenAI-compatible providers' baseUrl
            // (routing.yaml) is expected to already include their API
            // version segment (e.g. Groq's "https://api.groq.com/openai/v1"),
            // since that segment differs per provider and isn't this
            // adapter's business to hardcode.
            WireChatCompletionResponse wireResponse = restClient.post()
                    .uri("/chat/completions")
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
                .uri("/chat/completions")
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
