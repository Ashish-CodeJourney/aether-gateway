package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionRequestTest {

    @Test
    void rejectsBlankModel() {
        assertThatThrownBy(() -> new ChatCompletionRequest("", List.of(userMessage("hi")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsEmptyMessages() {
        assertThatThrownBy(() -> new ChatCompletionRequest("mock", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
    }

    @Test
    void rejectsMoreThanTheMaximumAllowedMessageCount() {
        List<ChatMessage> tooMany = java.util.stream.IntStream.range(0, ChatCompletionRequest.MAX_MESSAGES + 1)
                .mapToObj(i -> userMessage("message " + i))
                .toList();

        assertThatThrownBy(() -> new ChatCompletionRequest("mock", tooMany, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages")
                .hasMessageContaining(String.valueOf(ChatCompletionRequest.MAX_MESSAGES));
    }

    @Test
    void acceptsExactlyTheMaximumAllowedMessageCount() {
        List<ChatMessage> exactlyMax = java.util.stream.IntStream.range(0, ChatCompletionRequest.MAX_MESSAGES)
                .mapToObj(i -> userMessage("message " + i))
                .toList();

        var request = new ChatCompletionRequest("mock", exactlyMax, false);

        assertThat(request.messages()).hasSize(ChatCompletionRequest.MAX_MESSAGES);
    }

    @Test
    void acceptsAValidNonStreamingRequest() {
        var request = new ChatCompletionRequest("mock", List.of(userMessage("hello")), false);

        assertThat(request.model()).isEqualTo("mock");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.stream()).isFalse();
    }

    @Test
    void messagesListIsImmutable() {
        var mutable = new java.util.ArrayList<ChatMessage>();
        mutable.add(userMessage("hello"));
        var request = new ChatCompletionRequest("mock", mutable, false);

        mutable.add(userMessage("appended after construction"));

        assertThat(request.messages()).hasSize(1);
    }

    private static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content);
    }
}
