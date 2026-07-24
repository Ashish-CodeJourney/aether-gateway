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
}
