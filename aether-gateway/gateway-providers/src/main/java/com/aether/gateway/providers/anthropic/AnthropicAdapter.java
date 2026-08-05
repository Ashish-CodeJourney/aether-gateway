package com.aether.gateway.providers.anthropic;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicContentBlock;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicMessage;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicRequest;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicResponse;
import com.aether.gateway.providers.anthropic.wire.WireAnthropicStreamEvent;
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
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Anthropic's Messages API - the fourth distinct wire shape behind the
 * one {@link ProviderAdapter} port, and the least OpenAI-like of them:
 *
 * <ul>
 *   <li>auth is {@code x-api-key} plus a required {@code anthropic-version}
 *       header, not a bearer token;</li>
 *   <li>a system prompt is a top-level {@code system} string, not a
 *       message with {@code role: "system"};</li>
 *   <li>assistant output is a list of typed content blocks, not a
 *       string;</li>
 *   <li>{@code max_tokens} is required. The domain
 *       {@link ChatCompletionRequest} carries no such field, so this
 *       adapter supplies a configured default - see
 *       {@code defaultMaxTokens};</li>
 *   <li>the stream is a sequence of named event types
 *       ({@code message_start}, {@code content_block_delta},
 *       {@code message_stop}, ...) rather than one repeated chunk shape
 *       ended by a {@code [DONE]} sentinel.</li>
 * </ul>
 *
 * <p><strong>Not verified against the live API.</strong> Every test
 * behind this adapter runs against MockWebServer with fixtures written
 * from Anthropic's published wire format; no request from this code has
 * ever reached api.anthropic.com. Treat it as unproven until someone
 * runs it with a real key. This is stated here rather than left for a
 * user to discover.
 */
public class AnthropicAdapter implements ProviderAdapter {

    /**
     * Pinned rather than tracking "latest": Anthropic's versioning
     * contract is that a pinned version keeps its response shape, so
     * upgrading is a deliberate change with a test run behind it, not
     * something that happens to this gateway overnight.
     */
    private static final String API_VERSION = "2023-06-01";

    private final String providerName;
    private final int defaultMaxTokens;
    private final RestClient restClient;
    private final WebClient webClient;
    private final JsonMapper streamMapper;

    /**
     * @param defaultMaxTokens sent as {@code max_tokens} on every
     *                         request, since the API rejects a request
     *                         without it and the domain model has
     *                         nowhere to carry a caller-supplied value.
     */
    public AnthropicAdapter(
            String providerName,
            String baseUrl,
            String apiKey,
            int defaultMaxTokens,
            RestClient.Builder restBuilder,
            WebClient.Builder webBuilder) {
        if (defaultMaxTokens < 1) {
            throw new IllegalArgumentException("defaultMaxTokens must be at least 1, was " + defaultMaxTokens);
        }
        this.providerName = providerName;
        this.defaultMaxTokens = defaultMaxTokens;
        // Anthropic's wire format is snake_case (max_tokens, stop_reason,
        // input_tokens). Nulls are omitted because the API rejects
        // "system": null outright - absent and null are not the same
        // thing to it.
        var mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        this.streamMapper = mapper;

        var rest = restBuilder
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", API_VERSION)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(mapper));
                });
        var web = webBuilder
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", API_VERSION);
        if (apiKey != null && !apiKey.isBlank()) {
            rest = rest.defaultHeader("x-api-key", apiKey);
            web = web.defaultHeader("x-api-key", apiKey);
        }
        this.restClient = rest.build();
        this.webClient = web.build();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ProviderResponse invoke(ChatCompletionRequest request) {
        try {
            WireAnthropicResponse wireResponse = restClient.post()
                    .uri("/messages")
                    .body(toWireRequest(request, false))
                    .retrieve()
                    .body(WireAnthropicResponse.class);
            return new ProviderResponse.Completion(toDomainResponse(wireResponse));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            return new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, isRetryable(status));
        } catch (RestClientException e) {
            return new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true);
        }
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
        String responseId = providerName + "-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri("/messages")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toWireRequest(request, true))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .map(sse -> streamMapper.readValue(sse.data(), WireAnthropicStreamEvent.class))
                // message_start, content_block_start, ping and
                // content_block_stop carry no text and do not terminate
                // the stream; forwarding them would pad it with empty
                // chunks every client then has to filter out.
                .filter(event -> "content_block_delta".equals(event.type()) || "message_stop".equals(event.type()))
                .map(event -> {
                    boolean last = "message_stop".equals(event.type());
                    String text = event.delta() != null && event.delta().text() != null ? event.delta().text() : "";
                    return new ProviderResponse.StreamChunk(responseId, index.getAndIncrement(), text, last);
                });

        return JdkFlowAdapter.publisherToFlowPublisher(flux);
    }

    /**
     * 429 and 529 (Anthropic's own "overloaded") are explicit try-again
     * signals, and 5xx is worth another provider in the chain. Anything
     * else - a bad key, a malformed request, an unknown model - fails
     * identically on every retry, so retrying only spends the request's
     * timeout budget and delays the real error reaching the client.
     */
    private boolean isRetryable(int status) {
        return status == 429 || status == 529 || (status >= 500 && status < 600);
    }

    private WireAnthropicRequest toWireRequest(ChatCompletionRequest request, boolean stream) {
        List<WireAnthropicMessage> messages = request.messages().stream()
                .filter(m -> !"system".equals(m.role()))
                .map(m -> new WireAnthropicMessage(m.role(), m.content()))
                .toList();

        String system = request.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));

        return new WireAnthropicRequest(
                request.model(),
                messages,
                defaultMaxTokens,
                system.isBlank() ? null : system,
                stream);
    }

    private ChatCompletionResponse toDomainResponse(WireAnthropicResponse wire) {
        String text = wire.content() == null ? "" : wire.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(WireAnthropicContentBlock::text)
                .collect(Collectors.joining());

        var choice = new ChatCompletionChoice(
                0, new ChatMessage("assistant", text), mapStopReason(wire.stopReason()));
        Usage usage = Usage.of(
                wire.usage() != null && wire.usage().inputTokens() != null ? wire.usage().inputTokens() : 0,
                wire.usage() != null && wire.usage().outputTokens() != null ? wire.usage().outputTokens() : 0);

        return new ChatCompletionResponse(
                wire.id(), "chat.completion", System.currentTimeMillis() / 1000,
                wire.model(), List.of(choice), usage);
    }

    /** Anthropic's stop_reason vocabulary differs from the OpenAI-style one the domain model carries. */
    private String mapStopReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }
}
