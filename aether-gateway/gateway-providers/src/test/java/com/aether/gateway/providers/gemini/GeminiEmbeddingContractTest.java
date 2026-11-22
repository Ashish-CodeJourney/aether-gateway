package com.aether.gateway.providers.gemini;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.EmbeddingAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures follow Gemini's documented {@code batchEmbedContents} shape.
 * The batch endpoint is used even for a single input so there is one
 * code path; its response carries no token counts at all, so usage is
 * the gateway's own estimate.
 */
class GeminiEmbeddingContractTest extends EmbeddingAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new GeminiAdapter("gemini-test", baseUrl, "test-api-key", RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse embeddingSuccessResponse() {
        return jsonResponse(200, """
                {
                  "embeddings": [
                    {"values": [0.1, 0.2, 0.3]},
                    {"values": [0.4, 0.5, 0.6]}
                  ]
                }
                """);
    }

    @Override
    protected int expectedPromptTokens() {
        return helloWorldRequest(null).estimatedPromptTokens();
    }

    @Override
    protected MockResponse rateLimitResponse() {
        return jsonResponse(429, """
                {"error": {"code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED"}}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": {"code": 500, "message": "Internal error encountered.", "status": "INTERNAL"}}
                """);
    }

    @Test
    void postsOneBatchRequestPerCallWithTheKeyAsAQueryParameter() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(null));

        var recorded = takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1beta/models/embed-model:batchEmbedContents?key=test-api-key");
        assertThat(recorded.getBody().readUtf8())
                .contains("\"model\":\"models/embed-model\"")
                .contains("{\"parts\":[{\"text\":\"hello\"}]}")
                .contains("{\"parts\":[{\"text\":\"world\"}]}")
                .doesNotContain("outputDimensionality");
    }

    @Test
    void mapsRequestedDimensionsToOutputDimensionality() throws InterruptedException {
        server.enqueue(embeddingSuccessResponse());

        adapter.embed(helloWorldRequest(768));

        assertThat(takeRequest().getBody().readUtf8()).contains("\"outputDimensionality\":768");
    }
}
