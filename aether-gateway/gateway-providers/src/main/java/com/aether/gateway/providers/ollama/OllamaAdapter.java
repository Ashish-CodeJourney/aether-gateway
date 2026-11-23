package com.aether.gateway.providers.ollama;

import com.aether.gateway.providers.StreamFailures;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ollama.wire.WireOllamaChatRequest;
import com.aether.gateway.providers.ollama.wire.WireOllamaChatResponse;
import com.aether.gateway.providers.ollama.wire.WireOllamaEmbedRequest;
import com.aether.gateway.providers.ollama.wire.WireOllamaEmbedResponse;
import com.aether.gateway.providers.ollama.wire.WireOllamaMessage;
import com.aether.gateway.providers.ollama.wire.WireOllamaToolCall;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonDecoder;
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
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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
 *
 * <p>Tool calling is translated to and from OpenAI's shape by
 * {@link OllamaToolTranslation}, including its refusal of a
 * {@code tool_choice} Ollama cannot enforce.
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
        // The NDJSON stream's final line carries prompt_eval_count and
        // eval_count, which WebClient's default camelCase decoder drops;
        // the request's tool_calls/tool_name need the same mapper on the
        // way out.
        var webBuilderWithAuth = webBuilder
                .baseUrl(baseUrl)
                .codecs(codecs -> {
                    codecs.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(snakeCaseMapper));
                    codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(snakeCaseMapper));
                });
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
        Optional<String> unsupportedToolChoice = OllamaToolTranslation.unsupportedToolChoice(providerName, request.toolChoice());
        if (unsupportedToolChoice.isPresent()) {
            return new ProviderResponse.ProviderError(OllamaToolTranslation.TOOL_CHOICE_NOT_SUPPORTED, unsupportedToolChoice.get(), 400, false);
        }
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
        Optional<String> unsupportedToolChoice = OllamaToolTranslation.unsupportedToolChoice(providerName, request.toolChoice());
        if (unsupportedToolChoice.isPresent()) {
            return JdkFlowAdapter.publisherToFlowPublisher(Flux.error(new ProviderStreamException(
                    400, OllamaToolTranslation.TOOL_CHOICE_NOT_SUPPORTED, unsupportedToolChoice.get(), false)));
        }
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger toolCallOrdinal = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri("/api/chat")
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(toWireRequest(request, true))
                .retrieve()
                .bodyToFlux(WireOllamaChatResponse.class)
                .map(chunk -> {
                    List<ToolCallDelta> toolCallDeltas = toolCallsOf(chunk.message()).stream()
                            .map(call -> OllamaToolTranslation.deltaFromWire(toolCallOrdinal.getAndIncrement(), call))
                            .toList();
                    // Ollama's done_reason stays "stop" after a tool call;
                    // OpenAI clients look for "tool_calls" to know they
                    // must run the tools.
                    String finishReason = chunk.done() && toolCallOrdinal.get() > 0 ? "tool_calls" : null;
                    return new ProviderResponse.StreamChunk(
                            responseId,
                            index.getAndIncrement(),
                            chunk.message() != null && chunk.message().content() != null ? chunk.message().content() : "",
                            chunk.done(),
                            toolCallDeltas,
                            finishReason,
                            streamedUsage(chunk));
                });

        return JdkFlowAdapter.publisherToFlowPublisher(flux.onErrorMap(StreamFailures::translate));
    }

    /** Ollama reports prompt_eval_count/eval_count on the final ({@code done}) line only. */
    private static Usage streamedUsage(WireOllamaChatResponse chunk) {
        if (!chunk.done() || (chunk.promptEvalCount() == null && chunk.evalCount() == null)) {
            return null;
        }
        return toUsage(chunk);
    }

    private static Usage toUsage(WireOllamaChatResponse wire) {
        return Usage.of(
                wire.promptEvalCount() != null ? wire.promptEvalCount() : 0,
                wire.evalCount() != null ? wire.evalCount() : 0);
    }

    /** {@code /api/embed} (batch, Ollama 0.3+), not the legacy single-prompt {@code /api/embeddings}. */
    @Override
    public EmbeddingOutcome embed(EmbeddingRequest request) {
        var wireRequest = new WireOllamaEmbedRequest(request.model(), request.inputs(), request.dimensions());
        try {
            WireOllamaEmbedResponse wireResponse = restClient.post()
                    .uri("/api/embed")
                    .body(wireRequest)
                    .retrieve()
                    .body(WireOllamaEmbedResponse.class);
            List<Embedding> data = IntStream.range(0, wireResponse.embeddings().size())
                    .mapToObj(i -> new Embedding(i, wireResponse.embeddings().get(i)))
                    .toList();
            int promptTokens = wireResponse.promptEvalCount() != null ? wireResponse.promptEvalCount() : request.estimatedPromptTokens();
            String model = wireResponse.model() != null ? wireResponse.model() : request.model();
            return new EmbeddingOutcome.Success(new EmbeddingResponse(model, data, promptTokens));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status == 503 || status == 504;
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, retryable));
        } catch (RestClientException e) {
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true));
        }
    }

    private WireOllamaChatRequest toWireRequest(ChatCompletionRequest request, boolean stream) {
        return new WireOllamaChatRequest(
                request.model(),
                OllamaToolTranslation.messages(request.messages()),
                stream,
                OllamaToolTranslation.tools(request.tools(), request.toolChoice()));
    }

    private static List<WireOllamaToolCall> toolCallsOf(WireOllamaMessage message) {
        return message == null || message.toolCalls() == null ? List.of() : message.toolCalls();
    }

    private ChatCompletionResponse toDomainResponse(String model, WireOllamaChatResponse wire) {
        List<WireOllamaToolCall> wireCalls = toolCallsOf(wire.message());
        List<ToolCall> toolCalls = IntStream.range(0, wireCalls.size())
                .mapToObj(i -> OllamaToolTranslation.fromWire(i, wireCalls.get(i)))
                .toList();
        String content = wire.message().content() == null ? "" : wire.message().content();
        String finishReason = !toolCalls.isEmpty() ? "tool_calls" : wire.done() ? "stop" : null;
        var choice = new ChatCompletionChoice(
                0,
                new ChatMessage(wire.message().role(), content, toolCalls, null),
                finishReason);
        Usage usage = toUsage(wire);
        return new ChatCompletionResponse(
                providerName + "-" + System.nanoTime(), "chat.completion", System.currentTimeMillis() / 1000,
                model, List.of(choice), usage);
    }
}
