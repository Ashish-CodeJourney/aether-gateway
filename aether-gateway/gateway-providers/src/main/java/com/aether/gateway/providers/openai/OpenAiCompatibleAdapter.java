package com.aether.gateway.providers.openai;

import com.aether.gateway.providers.StreamFailures;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.openai.wire.WireChatCompletionResponse;
import com.aether.gateway.providers.openai.wire.WireEmbeddingRequest;
import com.aether.gateway.providers.openai.wire.WireEmbeddingResponse;
import com.aether.gateway.providers.openai.wire.WireStreamChunkResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.json.JacksonJsonEncoder;
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
 * logged). The wire mapping itself, tool calling included, lives in
 * {@link OpenAiWireMapping} and is shared with the mock provider's
 * adapter, since both speak exactly the same public OpenAI wire shape.
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
        // The streaming request body must be snake_case too (tool_calls,
        // tool_call_id, tool_choice); WebClient's default encoder is not.
        var webBuilderWithAuth = webBuilder
                .baseUrl(baseUrl)
                .codecs(codecs -> codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(snakeCaseMapper)));
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
        var wireRequest = OpenAiWireMapping.toWireRequest(request, false);
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
            return new ProviderResponse.Completion(OpenAiWireMapping.toDomainResponse(wireResponse));
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
                .bodyValue(OpenAiWireMapping.toWireRequest(request, true))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .takeWhile(sse -> !"[DONE]".equals(sse.data()))
                .map(sse -> OpenAiWireMapping.toStreamChunk(
                        snakeCaseMapper.readValue(sse.data(), WireStreamChunkResponse.class), responseId, index.getAndIncrement()));

        return JdkFlowAdapter.publisherToFlowPublisher(flux.onErrorMap(StreamFailures::translate));
    }

    @Override
    public EmbeddingOutcome embed(EmbeddingRequest request) {
        var wireRequest = new WireEmbeddingRequest(request.model(), request.inputs(), "float", request.dimensions());
        try {
            WireEmbeddingResponse wireResponse = restClient.post()
                    .uri("/embeddings")
                    .body(wireRequest)
                    .retrieve()
                    .body(WireEmbeddingResponse.class);
            return new EmbeddingOutcome.Success(toDomainEmbeddings(request, wireResponse));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status == 503 || status == 504;
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, retryable));
        } catch (RestClientException e) {
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true));
        }
    }

    private EmbeddingResponse toDomainEmbeddings(EmbeddingRequest request, WireEmbeddingResponse wire) {
        List<Embedding> data = wire.data().stream()
                .map(d -> new Embedding(d.index(), d.embedding()))
                .toList();
        int promptTokens = wire.usage() != null && wire.usage().promptTokens() != null
                ? wire.usage().promptTokens() : request.estimatedPromptTokens();
        return new EmbeddingResponse(wire.model() != null ? wire.model() : request.model(), data, promptTokens);
    }

}
