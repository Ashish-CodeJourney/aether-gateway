package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.ActiveStreamTracker;
import com.aether.gateway.mockprovider.DefaultControlsHolder;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI's {@code stream_options.include_usage}: when asked, the stream
 * ends with one extra chunk carrying {@code usage} and an empty
 * {@code choices} array, just before {@code [DONE]}.
 */
class MockChatControllerStreamUsageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebTestClient client = WebTestClient
            .bindToController(new MockChatController(new DefaultControlsHolder(), new ActiveStreamTracker()))
            .build();

    private List<String> streamedEvents(String requestBody, String tokensHeader) {
        var request = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (tokensHeader != null) {
            request = request.header("X-Mock-Tokens", tokensHeader);
        }
        return request.bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void endsTheStreamWithAUsageOnlyChunkWhenAskedToIncludeUsage() {
        var events = streamedEvents("""
                {"model": "mock", "stream": true, "stream_options": {"include_usage": true},
                 "messages": [{"role": "user", "content": "sixteen chars.."}]}
                """, "7");

        assertThat(events.getLast()).isEqualTo("[DONE]");
        JsonNode usageChunk = JSON.readTree(events.get(events.size() - 2));
        assertThat(usageChunk.get("choices")).isEmpty();
        assertThat(usageChunk.get("usage")).isEqualTo(JSON.readTree("""
                {"prompt_tokens": 3, "completion_tokens": 7, "total_tokens": 10}
                """));
        JsonNode finishChunk = JSON.readTree(events.get(events.size() - 3));
        assertThat(finishChunk.get("choices").get(0).get("finish_reason").asString()).isEqualTo("stop");
        assertThat(finishChunk.has("usage")).isFalse();
    }

    @Test
    void sendsNoUsageChunkUnlessAsked() {
        var events = streamedEvents("""
                {"model": "mock", "stream": true, "messages": [{"role": "user", "content": "hi"}]}
                """, null);

        assertThat(events.getLast()).isEqualTo("[DONE]");
        assertThat(events.subList(0, events.size() - 1))
                .allSatisfy(event -> assertThat(JSON.readTree(event).has("usage")).isFalse());
    }
}
