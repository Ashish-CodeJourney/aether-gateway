package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.router.wire.WireChatCompletionRequest;
import com.aether.gateway.router.wire.WireChatCompletionResponse;
import com.aether.gateway.router.wire.WireChatMessage;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * M0's single hardcoded {@link ProviderCaller}: forwards every request to
 * the mock provider over HTTP. Replaced by a real ProviderAdapter-based
 * implementation in gateway-providers from Phase 05 onward (see
 * ProviderCaller's javadoc).
 */
public class MockProviderForwarder implements ProviderCaller {

    private final RestClient restClient;

    public MockProviderForwarder(RestClient.Builder builder, String mockProviderBaseUrl) {
        // The mock provider (and every real provider's OpenAI-compatible
        // wire format) uses snake_case field names. This RestClient is
        // built independently of Spring Boot's autoconfigured, globally
        // snake_case-configured Jackson mapper (see GatewayConfig), so it
        // needs its own snake_case-aware converter, not the library
        // default (camelCase). Spring Boot 4.1 / Spring Framework 7 use
        // Jackson 3 (groupId tools.jackson.core), not Jackson 2; the
        // converter type is JacksonJsonHttpMessageConverter, not the
        // deprecated MappingJackson2HttpMessageConverter.
        JsonMapper snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        this.restClient = builder
                .baseUrl(mockProviderBaseUrl)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    converters.add(new JacksonJsonHttpMessageConverter(snakeCaseMapper));
                })
                .build();
    }

    @Override
    public ProviderResponse call(ChatCompletionRequest request) {
        var wireRequest = toWireRequest(request);
        try {
            WireChatCompletionResponse wireResponse = restClient.post()
                    .uri("/v1/chat/completions")
                    .body(wireRequest)
                    .retrieve()
                    .body(WireChatCompletionResponse.class);
            return new ProviderResponse.Completion(toDomainResponse(wireResponse));
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status == 503 || status == 504;
            return new ProviderResponse.ProviderError("provider_error", e.getMessage(), status, retryable);
        } catch (RestClientException e) {
            return new ProviderResponse.ProviderError("provider_unreachable", e.getMessage(), 502, true);
        }
    }

    private WireChatCompletionRequest toWireRequest(ChatCompletionRequest request) {
        List<WireChatMessage> messages = request.messages().stream()
                .map(m -> new WireChatMessage(m.role(), m.content()))
                .toList();
        return new WireChatCompletionRequest(request.model(), messages, request.stream());
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
