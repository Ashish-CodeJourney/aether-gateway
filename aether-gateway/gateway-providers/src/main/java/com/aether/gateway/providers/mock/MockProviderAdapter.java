package com.aether.gateway.providers.mock;

import com.aether.gateway.providers.StreamFailures;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.mock.wire.WireEmbeddingRequest;
import com.aether.gateway.providers.mock.wire.WireEmbeddingResponse;
import com.aether.gateway.providers.openai.OpenAiWireMapping;
import com.aether.gateway.providers.openai.wire.WireChatCompletionResponse;
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
 * F2.1/F2.2: the mock provider's {@link ProviderAdapter}. Multiple
 * instances are registered (e.g. "mock-primary", "mock-fallback"), each
 * pointed at a different mock-provider process/URL, which is what lets
 * routing.yaml express a real failover chain in Phase 05 without any
 * real provider credentials (PRD section 14's whole reason for existing
 * first). Real providers (Ollama, Groq, Gemini) get sibling adapters in
 * Phase 12, conforming to the same {@link ProviderAdapter} contract,
 * with zero changes to gateway-router (the hexagonal boundary's payoff).
 * mock-provider speaks the OpenAI wire shape, so the mapping (tool calling
 * included) is {@link OpenAiWireMapping}, shared with the generic
 * OpenAI-compatible adapter.
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
        this.webClient = webBuilder
                .baseUrl(baseUrl)
                .codecs(codecs -> codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(snakeCaseMapper)))
                .build();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ProviderResponse invoke(ChatCompletionRequest request) {
        var wireRequest = OpenAiWireMapping.toWireRequest(request, false);
        try {
            WireChatCompletionResponse wireResponse = restClient.post()
                    .uri("/v1/chat/completions")
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
                .uri("/v1/chat/completions")
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
                    .uri("/v1/embeddings")
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
