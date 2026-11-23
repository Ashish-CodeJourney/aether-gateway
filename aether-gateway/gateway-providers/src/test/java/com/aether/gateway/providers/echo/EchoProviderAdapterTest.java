package com.aether.gateway.providers.echo;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.ProviderResponse;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EchoProviderAdapterTest {

    private final EchoProviderAdapter adapter = new EchoProviderAdapter("echo");

    private static ChatCompletionRequest request(String... userMessages) {
        List<ChatMessage> messages = java.util.Arrays.stream(userMessages).map(m -> new ChatMessage("user", m)).toList();
        return new ChatCompletionRequest("echo-1", messages, false);
    }

    @Test
    void repliesWithTheLastUserMessage() {
        var response = adapter.invoke(request("first", "say hello"));

        assertThat(response).isInstanceOfSatisfying(ProviderResponse.Completion.class, completion -> {
            var choice = completion.response().choices().getFirst();
            assertThat(choice.message()).isEqualTo(new ChatMessage("assistant", "Echo: say hello"));
            assertThat(choice.finishReason()).isEqualTo("stop");
            assertThat(completion.response().model()).isEqualTo("echo-1");
            assertThat(completion.response().object()).isEqualTo("chat.completion");
        });
    }

    @Test
    void reportsTokenUsage() {
        var completion = (ProviderResponse.Completion) adapter.invoke(request("say hello"));

        var usage = completion.response().usage();
        assertThat(usage.promptTokens()).isPositive();
        assertThat(usage.completionTokens()).isPositive();
        assertThat(usage.totalTokens()).isEqualTo(usage.promptTokens() + usage.completionTokens());
    }

    @Test
    void streamsTheSameReplyWordByWordEndingWithUsage() {
        List<ProviderResponse.StreamChunk> chunks = JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(request("say hello")))
                .collectList()
                .block();

        assertThat(chunks.stream().map(ProviderResponse.StreamChunk::deltaContent).collect(Collectors.joining()))
                .isEqualTo("Echo: say hello");
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getLast().last()).isTrue();
        assertThat(chunks.getLast().usage()).isNotNull();
        assertThat(chunks.subList(0, chunks.size() - 1)).noneMatch(ProviderResponse.StreamChunk::last);
    }

    @Test
    void hasNoEmbeddings() {
        assertThat(adapter.embed(new EmbeddingRequest("echo-1", List.of("hi"), null)))
                .isEqualTo(EmbeddingOutcome.unsupportedBy("echo"));
    }
}
