package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F4.1: exact-match cache keyed by SHA-256 of (normalised messages + model + params). */
class CacheKeyTest {

    @Test
    void producesA64CharacterHexSha256Digest() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, null);

        String hash = CacheKey.exactHash(request);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministicForTheSameRequest() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, null);

        assertThat(CacheKey.exactHash(requestA)).isEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void isInsensitiveToSurroundingAndCollapsedWhitespace() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello  there")), false, null, null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "  hello there  ")), false, null, null);

        assertThat(CacheKey.exactHash(requestA)).isEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenTheMessageContentDiffers() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "goodbye")), false, null, null);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenTheModelDiffers() {
        var requestA = new ChatCompletionRequest("model-a", List.of(new ChatMessage("user", "hello")), false, null, null);
        var requestB = new ChatCompletionRequest("model-b", List.of(new ChatMessage("user", "hello")), false, null, null);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenTemperatureDiffers() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, 0.1, null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, 0.2, null);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenMessageRoleDiffers() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("system", "hello")), false, null, null);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenTwoToolsShareANameButDifferInTheirSchema() {
        String narrow = "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{\"type\":\"object\"}}}";
        String wide = "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}";
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, List.of(narrow), null);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, List.of(wide), null);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenToolChoiceDiffers() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, List.of("{}"), "\"auto\"");
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hello")), false, null, List.of("{}"), "\"required\"");

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenAnAssistantToolCallsArgumentsDiffer() {
        var requestA = conversationWithToolCall("{\"city\":\"Paris\"}");
        var requestB = conversationWithToolCall("{\"city\":\"Rome\"}");

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    @Test
    void differsWhenAToolResultAnswersADifferentCall() {
        var requestA = new ChatCompletionRequest("mock", List.of(new ChatMessage("tool", "18C", List.of(), "call_1")), false);
        var requestB = new ChatCompletionRequest("mock", List.of(new ChatMessage("tool", "18C", List.of(), "call_2")), false);

        assertThat(CacheKey.exactHash(requestA)).isNotEqualTo(CacheKey.exactHash(requestB));
    }

    private static ChatCompletionRequest conversationWithToolCall(String arguments) {
        var call = new ToolCall("call_1", "function", "get_weather", arguments);
        return new ChatCompletionRequest("mock", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("assistant", "", List.of(call), null)), false);
    }
}
