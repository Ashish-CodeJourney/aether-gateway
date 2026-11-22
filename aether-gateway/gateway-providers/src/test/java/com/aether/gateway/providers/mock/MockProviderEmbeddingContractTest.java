package com.aether.gateway.providers.mock;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.EmbeddingAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/** mock-provider serves the OpenAI embeddings shape at {@code /v1/embeddings}, mirroring its chat endpoint. */
class MockProviderEmbeddingContractTest extends EmbeddingAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new MockProviderAdapter("mock-test", baseUrl, RestClient.builder(), WebClient.builder());
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
                  "model": "mock",
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
                {"type": "https://mock-provider.local/problems/429", "title": "429", "status": 429}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"type": "https://mock-provider.local/problems/500", "title": "500", "status": 500}
                """);
    }

    @Test
    void postsToTheVersionedEmbeddingsPath() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(4));

        var recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/embeddings");
        assertThat(recorded.getBody().readUtf8()).contains("\"dimensions\":4");
    }
}
