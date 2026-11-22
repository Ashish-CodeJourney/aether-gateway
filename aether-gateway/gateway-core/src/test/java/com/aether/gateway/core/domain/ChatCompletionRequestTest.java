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

    @Test
    void carriesToolDefinitionsAndToolChoiceAsOpaqueJson() {
        String tool = "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{\"type\":\"object\"}}}";

        var request = new ChatCompletionRequest("mock", List.of(userMessage("hi")), false, null, List.of(tool), "\"auto\"");

        assertThat(request.tools()).containsExactly(tool);
        assertThat(request.toolChoice()).isEqualTo("\"auto\"");
    }

    @Test
    void withModelKeepsEveryOtherFieldIncludingToolPayloads() {
        var request = new ChatCompletionRequest(
                "alias", List.of(userMessage("hi")), true, 0.2, List.of("{\"type\":\"function\"}"), "\"required\"");

        var rewritten = request.withModel("upstream-model");

        assertThat(rewritten).isEqualTo(new ChatCompletionRequest(
                "upstream-model", List.of(userMessage("hi")), true, 0.2, List.of("{\"type\":\"function\"}"), "\"required\""));
    }

    @Test
    void usesToolsIsTrueForToolDefinitionsOrToolMessagesInTheConversation() {
        var plain = new ChatCompletionRequest("mock", List.of(userMessage("hi")), false);
        var withDefinitions = new ChatCompletionRequest("mock", List.of(userMessage("hi")), false, null, List.of("{}"), null);
        var withToolResult = new ChatCompletionRequest(
                "mock", List.of(userMessage("hi"), new ChatMessage("tool", "18C", List.of(), "call_1")), false);

        assertThat(plain.usesTools()).isFalse();
        assertThat(withDefinitions.usesTools()).isTrue();
        assertThat(withToolResult.usesTools()).isTrue();
    }

    private static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content);
    }
}
