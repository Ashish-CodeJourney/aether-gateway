package com.aether.gateway.providers.gemini;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.gemini.wire.WireGeminiContent;
import com.aether.gateway.providers.gemini.wire.WireGeminiPart;
import com.aether.gateway.providers.gemini.wire.WireGeminiRequest;
import com.aether.gateway.providers.gemini.wire.WireGeminiResponse;
import com.aether.gateway.providers.gemini.wire.WireGeminiSystemInstruction;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * F2.2 (Phase 12/M9): Google Gemini. A third, genuinely distinct wire
 * shape from the other two adapters: requests are {@code contents}/
 * {@code parts} rather than a flat {@code messages} array, the
 * assistant role is spelled {@code "model"} rather than
 * {@code "assistant"}, a system message is its own top-level
 * {@code systemInstruction} field rather than a message in the array,
 * and the API key is a {@code ?key=} query parameter, not a bearer
 * header. Streaming uses real SSE ({@code ?alt=sse}) but with no
 * {@code [DONE]} sentinel - the stream just ends; "last" is signalled
 * by the final candidate carrying a non-null {@code finishReason},
 * exactly like the other two adapters' own "last" signal, so all three
 * still satisfy the identical {@link ProviderAdapter} port despite none
 * of the three sharing a wire format.
 */
public class GeminiAdapter implements ProviderAdapter {

    private final String providerName;
    private final String apiKey;
    private final RestClient restClient;
    private final WebClient webClient;

    public GeminiAdapter(
            String providerName, String baseUrl, String apiKey, RestClient.Builder restBuilder, WebClient.Builder webBuilder) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        // Deliberately NOT the SNAKE_CASE mapper the rest of this
        // project's providers use: Gemini's real wire format is
        // camelCase ("finishReason", "promptTokenCount"), unlike
        // OpenAI/Groq/Ollama's snake_case. A SNAKE_CASE mapper here
        // silently fails to bind "finishReason" (looks for
        // "finish_reason" instead), leaving it null on every response -
        // caught by the streaming contract test's "last chunk" assertion
        // (the non-streaming test's simpler content-only assertion
        // didn't happen to exercise the field and let it through).
        var camelCaseMapper = JsonMapper.builder().build();
        this.restClient = restBuilder
                .baseUrl(baseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(camelCaseMapper));
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
        var wireRequest = toWireRequest(request);
        try {
            WireGeminiResponse wireResponse = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParamIfPresent("key", java.util.Optional.ofNullable(apiKey))
                            .build(request.model()))
                    .body(wireRequest)
                    .retrieve()
                    .body(WireGeminiResponse.class);
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
        var camelCaseMapper = JsonMapper.builder().build();
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .queryParamIfPresent("key", java.util.Optional.ofNullable(apiKey))
                        .build(request.model()))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toWireRequest(request))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .map(sse -> {
                    WireGeminiResponse chunk = camelCaseMapper.readValue(sse.data(), WireGeminiResponse.class);
                    var candidate = chunk.candidates().get(0);
                    boolean last = candidate.finishReason() != null;
                    String content = candidate.content() != null && !candidate.content().parts().isEmpty()
                            ? candidate.content().parts().get(0).text() : "";
                    return new ProviderResponse.StreamChunk(responseId, index.getAndIncrement(), content, last);
                });

        return JdkFlowAdapter.publisherToFlowPublisher(flux);
    }

    private WireGeminiRequest toWireRequest(ChatCompletionRequest request) {
        List<WireGeminiContent> contents = request.messages().stream()
                .filter(m -> !"system".equals(m.role()))
                .map(m -> new WireGeminiContent(
                        "assistant".equals(m.role()) ? "model" : "user",
                        List.of(new WireGeminiPart(m.content()))))
                .toList();

        String systemText = request.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));
        WireGeminiSystemInstruction systemInstruction = systemText.isBlank()
                ? null : new WireGeminiSystemInstruction(List.of(new WireGeminiPart(systemText)));

        return new WireGeminiRequest(contents, systemInstruction);
    }

    private ChatCompletionResponse toDomainResponse(String model, WireGeminiResponse wire) {
        var candidate = wire.candidates().get(0);
        String text = candidate.content() != null && !candidate.content().parts().isEmpty()
                ? candidate.content().parts().get(0).text() : "";
        var choice = new ChatCompletionChoice(
                candidate.index(), new ChatMessage("assistant", text), mapFinishReason(candidate.finishReason()));
        var usageMeta = wire.usageMetadata();
        Usage usage = Usage.of(
                usageMeta != null && usageMeta.promptTokenCount() != null ? usageMeta.promptTokenCount() : 0,
                usageMeta != null && usageMeta.candidatesTokenCount() != null ? usageMeta.candidatesTokenCount() : 0);
        return new ChatCompletionResponse(
                providerName + "-" + System.nanoTime(), "chat.completion", System.currentTimeMillis() / 1000,
                model, List.of(choice), usage);
    }

    /** Gemini's finishReason values ("STOP", "MAX_TOKENS", ...) are uppercase; normalise to the OpenAI-style lowercase the rest of this project's domain model expects. */
    private String mapFinishReason(String geminiFinishReason) {
        if (geminiFinishReason == null) {
            return null;
        }
        return switch (geminiFinishReason) {
            case "MAX_TOKENS" -> "length";
            default -> "stop";
        };
    }
}
