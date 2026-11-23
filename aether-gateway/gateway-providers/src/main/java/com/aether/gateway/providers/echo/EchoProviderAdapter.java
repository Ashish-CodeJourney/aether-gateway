package com.aether.gateway.providers.echo;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * routing.yaml {@code type: echo}: answers every request itself by
 * echoing the last user message back, with no network call and no
 * credential. Exists so a single {@code docker run} of the lite image
 * can be tried end to end - routing, caching, quotas, the request log -
 * before any real provider is configured.
 */
public class EchoProviderAdapter implements ProviderAdapter {

    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=\\s)");

    private final String providerName;

    public EchoProviderAdapter(String providerName) {
        this.providerName = providerName;
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public ProviderResponse invoke(ChatCompletionRequest request) {
        String reply = replyTo(request);
        return new ProviderResponse.Completion(new ChatCompletionResponse(
                "chatcmpl-echo-" + UUID.randomUUID(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                request.model(),
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", reply), "stop")),
                usageFor(request, reply)));
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
        String reply = replyTo(request);
        String id = "chatcmpl-echo-" + UUID.randomUUID();
        List<String> words = List.of(WORD_BOUNDARY.split(reply));
        int lastIndex = words.size() - 1;
        Flux<ProviderResponse.StreamChunk> chunks = Flux.fromStream(IntStream.rangeClosed(0, lastIndex)
                .mapToObj(i -> i == lastIndex
                        ? new ProviderResponse.StreamChunk(id, i, words.get(i), true, List.of(), "stop", usageFor(request, reply))
                        : new ProviderResponse.StreamChunk(id, i, words.get(i), false)));
        return JdkFlowAdapter.publisherToFlowPublisher(chunks);
    }

    private static String replyTo(ChatCompletionRequest request) {
        String lastUserMessage = request.messages().reversed().stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
        return "Echo: " + lastUserMessage;
    }

    private static Usage usageFor(ChatCompletionRequest request, String reply) {
        int promptCharacters = request.messages().stream().mapToInt(message -> message.content().length()).sum();
        return Usage.of(approximateTokens(promptCharacters), approximateTokens(reply.length()));
    }

    private static int approximateTokens(int characters) {
        return Math.max(1, characters / 4);
    }
}
