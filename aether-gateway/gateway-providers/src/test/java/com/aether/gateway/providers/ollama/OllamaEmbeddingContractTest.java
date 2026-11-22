package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.EmbeddingAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures follow Ollama's documented {@code POST /api/embed} shape (not the legacy single-input {@code /api/embeddings}). */
class OllamaEmbeddingContractTest extends EmbeddingAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new OllamaAdapter("ollama-test", baseUrl, null, RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse embeddingSuccessResponse() {
        return jsonResponse(200, """
                {
                  "model": "nomic-embed-text",
                  "embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]],
                  "total_duration": 14143917,
                  "load_duration": 1019500,
                  "prompt_eval_count": 4
                }
                """);
    }

    @Override
    protected int expectedPromptTokens() {
        return 4;
    }

    @Override
    protected MockResponse rateLimitResponse() {
        return jsonResponse(429, """
                {"error": "too many requests"}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": "model failed to load"}
                """);
    }

    @Test
    void postsToTheEmbedEndpointWithTheInputArray() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(null));

        var recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/embed");
        assertThat(recorded.getBody().readUtf8())
                .contains("\"model\":\"embed-model\"")
                .contains("\"input\":[\"hello\",\"world\"]")
                .doesNotContain("dimensions");
    }

    @Test
    void passesRequestedDimensionsThrough() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(128));

        assertThat(takeRequest().getBody().readUtf8()).contains("\"dimensions\":128");
    }

    @Test
    void fallsBackToAnEstimateWhenOllamaOmitsThePromptTokenCount() {
        server.enqueue(jsonResponse(200, """
                {"model": "nomic-embed-text", "embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]}
                """));

        var outcome = (EmbeddingOutcome.Success) adapter.embed(helloWorldRequest(null));

        assertThat(outcome.response().promptTokens()).isEqualTo(helloWorldRequest(null).estimatedPromptTokens());
    }
}
