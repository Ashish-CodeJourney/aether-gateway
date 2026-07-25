package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ollama.wire.WireOllamaChatRequest;
import com.aether.gateway.providers.ollama.wire.WireOllamaChatResponse;
import com.aether.gateway.providers.ollama.wire.WireOllamaMessage;
import org.springframework.http.MediaType;
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
 * F2.2 (Phase 12/M9): local Ollama serving. Ollama's own wire format
 * (POST {@code /api/chat}), not OpenAI's - no "delta"/"chunk" streaming
 * envelope, just its own response object repeated per NDJSON line with
 * {@code done=false} until the final line. Proves the {@link ProviderAdapter}
 * port is a real abstraction: this adapter's HTTP shape has nothing in
 * common with {@link com.aether.gateway.providers.openai.OpenAiCompatibleAdapter}'s,
 * yet both satisfy the identical port with zero changes to gateway-core
 * or gateway-router.
 *
 * <p>{@code apiKey} is almost always {@code null} for a local Ollama
 * instance (it has no built-in auth), but is honoured as an optional
 * Bearer header for the case where an operator has put Ollama behind an
 * authenticating reverse proxy.
 */
public class OllamaAdapter implements ProviderAdapter {

    private final String providerName;
    private final RestClient restClient;
    private final WebClient webClient;

    public OllamaAdapter(
            String providerName, String baseUrl, String apiKey, RestClient.Builder restBuilder, WebClient.Builder webBuilder) {
        this.providerName = providerName;
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
            WireOllamaChatResponse wireResponse = restClient.post()
                    .uri("/api/chat")
                    .body(wireRequest)
                    .retrieve()
                    .body(WireOllamaChatResponse.class);
            return new ProviderResponse.Completion(toDomainResponse(request.model(), wireResponse));
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
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri("/api/chat")
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(toWireRequest(request, true))
                .retrieve()
                .bodyToFlux(WireOllamaChatResponse.class)
                .map(chunk -> new ProviderResponse.StreamChunk(
                        responseId,
                        index.getAndIncrement(),
                        chunk.message() != null && chunk.message().content() != null ? chunk.message().content() : "",
                        chunk.done()));

        return JdkFlowAdapter.publisherToFlowPublisher(flux);
    }

    private WireOllamaChatRequest toWireRequest(ChatCompletionRequest request, boolean stream) {
        List<WireOllamaMessage> messages = request.messages().stream()
                .map(m -> new WireOllamaMessage(m.role(), m.content()))
                .toList();
        return new WireOllamaChatRequest(request.model(), messages, stream);
    }

    private ChatCompletionResponse toDomainResponse(String model, WireOllamaChatResponse wire) {
        var choice = new ChatCompletionChoice(
                0,
                new ChatMessage(wire.message().role(), wire.message().content()),
                wire.done() ? "stop" : null);
        Usage usage = Usage.of(
                wire.promptEvalCount() != null ? wire.promptEvalCount() : 0,
                wire.evalCount() != null ? wire.evalCount() : 0);
        return new ChatCompletionResponse(
                providerName + "-" + System.nanoTime(), "chat.completion", System.currentTimeMillis() / 1000,
                model, List.of(choice), usage);
    }
}
