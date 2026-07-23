package com.aether.gateway.router;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.router.wire.WireChatCompletionRequest;
import com.aether.gateway.router.wire.WireChatMessage;
import com.aether.gateway.router.wire.WireStreamChunkResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M1's single hardcoded {@link StreamingProviderCaller}: forwards every
 * streaming request to the mock provider over SSE. Replaced by a real
 * ProviderAdapter-based implementation in Phase 05, same as
 * {@link MockProviderForwarder}.
 */
public class MockProviderStreamForwarder implements StreamingProviderCaller {

    private final WebClient webClient;
    private final ObjectMapper snakeCaseMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public MockProviderStreamForwarder(WebClient.Builder builder, String mockProviderBaseUrl) {
        this.webClient = builder.baseUrl(mockProviderBaseUrl).build();
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> callStreaming(ChatCompletionRequest request) {
        String responseId = "stream-" + System.nanoTime();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ProviderResponse.StreamChunk> flux = webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toWireRequest(request))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .takeWhile(sse -> !"[DONE]".equals(sse.data()))
                .map(sse -> toStreamChunk(sse, responseId, index.getAndIncrement()));

        return JdkFlowAdapter.publisherToFlowPublisher(flux);
    }

    private ProviderResponse.StreamChunk toStreamChunk(ServerSentEvent<String> sse, String responseId, int index) {
        WireStreamChunkResponse chunk = snakeCaseMapper.readValue(sse.data(), WireStreamChunkResponse.class);
        var choice = chunk.choices().get(0);
        boolean last = choice.finishReason() != null;
        String content = choice.delta() != null && choice.delta().content() != null ? choice.delta().content() : "";
        return new ProviderResponse.StreamChunk(responseId, index, content, last);
    }

    private WireChatCompletionRequest toWireRequest(ChatCompletionRequest request) {
        List<WireChatMessage> messages = request.messages().stream()
                .map(m -> new WireChatMessage(m.role(), m.content()))
                .toList();
        return new WireChatCompletionRequest(request.model(), messages, true);
    }
}
