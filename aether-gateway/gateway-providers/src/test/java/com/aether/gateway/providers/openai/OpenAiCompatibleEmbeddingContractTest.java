package com.aether.gateway.providers.openai;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.EmbeddingAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingContractTest extends EmbeddingAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new OpenAiCompatibleAdapter(
                "openai-compatible-test", baseUrl, "test-api-key", RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse embeddingSuccessResponse() {
        return jsonResponse(200, """
                {
                  "object": "list",
                  "data": [
                    {"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]},
                    {"object": "embedding", "index": 1, "embedding": [0.4, 0.5, 0.6]}
                  ],
                  "model": "text-embedding-3-small",
                  "usage": {"prompt_tokens": 2, "total_tokens": 2}
                }
                """);
    }

    @Override
    protected int expectedPromptTokens() {
        return 2;
    }

    @Override
    protected MockResponse rateLimitResponse() {
        return jsonResponse(429, """
                {"error": {"message": "Rate limit reached", "type": "rate_limit_error"}}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": {"message": "Internal server error", "type": "server_error"}}
                """);
    }

    @Test
    void postsToTheEmbeddingsPathUnderTheConfiguredBaseUrlWithBearerAuth() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(null));

        var recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/embeddings");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
    }

    @Test
    void asksForFloatsAndOmitsDimensionsWhenNotRequested() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(null));

        String body = takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"encoding_format\":\"float\"").doesNotContain("dimensions");
        assertThat(body).contains("\"model\":\"embed-model\"");
    }

    @Test
    void passesRequestedDimensionsThrough() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(256));

        assertThat(takeRequest().getBody().readUtf8()).contains("\"dimensions\":256");
    }

    @Test
    void reportsTheUpstreamModelName() {
        server.enqueue(embeddingSuccessResponse());

        var outcome = (com.aether.gateway.core.domain.EmbeddingOutcome.Success) adapter.embed(helloWorldRequest(null));

        assertThat(outcome.response().model()).isEqualTo("text-embedding-3-small");
    }
}
