package com.aether.gateway.providers.anthropic;

import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Anthropic has no embeddings API; asking for one must be a clean, non-retryable refusal with no HTTP call made. */
class AnthropicEmbeddingUnsupportedTest {

    @Test
    void refusesEmbeddingsWithoutCallingTheProvider() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var adapter = new AnthropicAdapter(
                    "anthropic-test", server.url("/").toString(), "key", 1024, RestClient.builder(), WebClient.builder());

            var outcome = adapter.embed(new EmbeddingRequest("claude", List.of("hello"), null));

            var error = ((EmbeddingOutcome.Failure) outcome).error();
            assertThat(error.errorCode()).isEqualTo(EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED);
            assertThat(error.retryable()).isFalse();
            assertThat(error.message()).contains("anthropic-test");
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
