package com.aether.gateway.providers.gemini;

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
import com.aether.gateway.providers.gemini.wire.WireGeminiBatchEmbedRequest;
import com.aether.gateway.providers.gemini.wire.WireGeminiBatchEmbedResponse;
import com.aether.gateway.providers.gemini.wire.WireGeminiCandidate;
import com.aether.gateway.providers.gemini.wire.WireGeminiContent;
import com.aether.gateway.providers.gemini.wire.WireGeminiEmbedContent;
import com.aether.gateway.providers.gemini.wire.WireGeminiEmbedContentRequest;
import com.aether.gateway.providers.gemini.wire.WireGeminiFunctionCall;
import com.aether.gateway.providers.gemini.wire.WireGeminiPart;
import com.aether.gateway.providers.gemini.wire.WireGeminiRequest;
import com.aether.gateway.providers.gemini.wire.WireGeminiResponse;
import com.aether.gateway.providers.gemini.wire.WireGeminiSystemInstruction;
import com.aether.gateway.providers.gemini.wire.WireGeminiUsageMetadata;
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
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 *
 * <p>Tool calling is translated to and from OpenAI's shape: requests in
 * {@link GeminiRequestTranslation} (with JSON Schema converted by
 * {@link GeminiSchemaConversion}), and {@code functionCall} reply parts
 * here, as {@code tool_calls} with generated ids and a
 * {@code tool_calls} finish reason.
 */
public class GeminiAdapter implements ProviderAdapter {

    private static final String INVALID_REQUEST = "invalid_request";

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
        WireGeminiRequest wireRequest;
        try {
            wireRequest = toWireRequest(request);
        } catch (GeminiRequestTranslation.UnknownToolCallIdException e) {
            return new ProviderResponse.ProviderError(INVALID_REQUEST, e.getMessage(), 400, false);
        }
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
        WireGeminiRequest wireRequest;
        try {
            wireRequest = toWireRequest(request);
        } catch (GeminiRequestTranslation.UnknownToolCallIdException e) {
            return JdkFlowAdapter.publisherToFlowPublisher(
                    Flux.error(new ProviderStreamException(400, INVALID_REQUEST, e.getMessage(), false)));
        }
        var camelCaseMapper = JsonMapper.builder().build();
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger toolCallOrdinal = new AtomicInteger(0);
        AtomicReference<WireGeminiUsageMetadata> latestUsage = new AtomicReference<>();

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .queryParamIfPresent("key", java.util.Optional.ofNullable(apiKey))
                        .build(request.model()))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(wireRequest)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .map(sse -> {
                    WireGeminiResponse chunk = camelCaseMapper.readValue(sse.data(), WireGeminiResponse.class);
                    var candidate = chunk.candidates().get(0);
                    boolean last = candidate.finishReason() != null;
                    List<WireGeminiPart> parts = partsOf(candidate);
                    // Gemini sends each function call whole, never split
                    // across chunks, so one delta carries id, name and the
                    // complete arguments. Calls are numbered across the
                    // whole stream, as OpenAI clients index them.
                    List<ToolCallDelta> toolCallDeltas = parts.stream()
                            .filter(part -> part.functionCall() != null)
                            .map(part -> toToolCallDelta(toolCallOrdinal.getAndIncrement(), part.functionCall()))
                            .toList();
                    if (chunk.usageMetadata() != null) {
                        latestUsage.set(chunk.usageMetadata());
                    }
                    // usageMetadata is a running count repeated on every
                    // chunk, so only the finishing chunk reports it.
                    Usage usage = last && latestUsage.get() != null ? toUsage(latestUsage.get()) : null;
                    // Gemini reports STOP even when it ended on a function
                    // call; OpenAI clients look for "tool_calls" to know
                    // they must run the tools.
                    String finishReason = last && toolCallOrdinal.get() > 0 ? "tool_calls" : null;
                    return new ProviderResponse.StreamChunk(
                            responseId, index.getAndIncrement(), replyText(parts), last, toolCallDeltas, finishReason, usage);
                });

        return JdkFlowAdapter.publisherToFlowPublisher(flux.onErrorMap(StreamFailures::translate));
    }

    /**
     * Always {@code batchEmbedContents}, even for one input, so there is a
     * single code path. Gemini's embedding responses carry no token
     * counts, so usage is the gateway's own estimate.
     */
    @Override
    public EmbeddingOutcome embed(EmbeddingRequest request) {
        String qualifiedModel = "models/" + request.model();
        var wireRequest = new WireGeminiBatchEmbedRequest(request.inputs().stream()
                .map(text -> new WireGeminiEmbedContentRequest(
                        qualifiedModel, new WireGeminiEmbedContent(List.of(new WireGeminiPart(text))), request.dimensions()))
                .toList());
        try {
            WireGeminiBatchEmbedResponse wireResponse = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:batchEmbedContents")
                            .queryParamIfPresent("key", java.util.Optional.ofNullable(apiKey))
                            .build(request.model()))
                    .body(wireRequest)
                    .retrieve()
                    .body(WireGeminiBatchEmbedResponse.class);
            List<Embedding> data = IntStream.range(0, wireResponse.embeddings().size())
                    .mapToObj(i -> new Embedding(i, wireResponse.embeddings().get(i).values()))
                    .toList();
            return new EmbeddingOutcome.Success(new EmbeddingResponse(request.model(), data, request.estimatedPromptTokens()));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status == 503 || status == 504;
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, retryable));
        } catch (RestClientException e) {
            return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true));
        }
    }

    private WireGeminiRequest toWireRequest(ChatCompletionRequest request) {
        List<WireGeminiContent> contents = GeminiRequestTranslation.contents(request.messages());

        String systemText = request.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));
        WireGeminiSystemInstruction systemInstruction = systemText.isBlank()
                ? null : new WireGeminiSystemInstruction(List.of(new WireGeminiPart(systemText)));

        return new WireGeminiRequest(
                contents,
                systemInstruction,
                GeminiRequestTranslation.tools(request.tools()),
                GeminiRequestTranslation.toolConfig(request.toolChoice()));
    }

    private ChatCompletionResponse toDomainResponse(String model, WireGeminiResponse wire) {
        var candidate = wire.candidates().get(0);
        List<WireGeminiPart> parts = partsOf(candidate);
        List<WireGeminiFunctionCall> functionCalls = parts.stream()
                .map(WireGeminiPart::functionCall)
                .filter(Objects::nonNull)
                .toList();
        List<ToolCall> toolCalls = IntStream.range(0, functionCalls.size())
                .mapToObj(i -> toToolCall(i, functionCalls.get(i)))
                .toList();
        String finishReason = toolCalls.isEmpty() ? mapFinishReason(candidate.finishReason()) : "tool_calls";
        var choice = new ChatCompletionChoice(
                candidate.index(), new ChatMessage("assistant", replyText(parts), toolCalls, null), finishReason);
        Usage usage = wire.usageMetadata() == null ? Usage.of(0, 0) : toUsage(wire.usageMetadata());
        return new ChatCompletionResponse(
                providerName + "-" + System.nanoTime(), "chat.completion", System.currentTimeMillis() / 1000,
                model, List.of(choice), usage);
    }

    private static List<WireGeminiPart> partsOf(WireGeminiCandidate candidate) {
        return candidate.content() == null || candidate.content().parts() == null ? List.of() : candidate.content().parts();
    }

    /** Every text part, joined; reasoning-summary ("thought") parts are not reply text. */
    private static String replyText(List<WireGeminiPart> parts) {
        return parts.stream()
                .filter(part -> part.text() != null && !Boolean.TRUE.equals(part.thought()))
                .map(WireGeminiPart::text)
                .collect(Collectors.joining());
    }

    /**
     * The Gemini API usually returns no call id, while OpenAI clients need
     * one to pair each tool result with its call. The generated
     * {@code call_<ordinal>_<name>} is unique within a reply and
     * deterministic, so the same reply always yields the same ids.
     */
    private static ToolCall toToolCall(int ordinal, WireGeminiFunctionCall call) {
        String id = call.id() != null && !call.id().isBlank() ? call.id() : "call_" + ordinal + "_" + call.name();
        String arguments = call.args() == null || call.args().isNull() ? "{}" : call.args().toString();
        return new ToolCall(id, "function", call.name(), arguments);
    }

    private static ToolCallDelta toToolCallDelta(int ordinal, WireGeminiFunctionCall functionCall) {
        ToolCall call = toToolCall(ordinal, functionCall);
        return new ToolCallDelta(ordinal, call.id(), call.functionName(), call.argumentsJson());
    }

    private static Usage toUsage(WireGeminiUsageMetadata usageMeta) {
        return Usage.of(
                usageMeta.promptTokenCount() != null ? usageMeta.promptTokenCount() : 0,
                usageMeta.candidatesTokenCount() != null ? usageMeta.candidatesTokenCount() : 0);
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
