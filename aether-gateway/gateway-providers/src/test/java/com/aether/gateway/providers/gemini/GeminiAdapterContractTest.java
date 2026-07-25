package com.aether.gateway.providers.gemini;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiAdapterContractTest extends ProviderAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new GeminiAdapter("gemini-test", baseUrl, "test-api-key", RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse successResponse() {
        return jsonResponse(200, """
                {
                  "candidates": [
                    {
                      "content": {"parts": [{"text": "Hello there"}], "role": "model"},
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ],
                  "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2, "totalTokenCount": 7}
                }
                """);
    }

    @Override
    protected String expectedSuccessContent() {
        return "Hello there";
    }

    @Override
    protected MockResponse rateLimitResponse() {
        return jsonResponse(429, """
                {"error": {"code": 429, "message": "Resource exhausted", "status": "RESOURCE_EXHAUSTED"}}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": {"code": 500, "message": "Internal error", "status": "INTERNAL"}}
                """);
    }

    @Override
    protected MockResponse streamingResponse() {
        String body = """
                data: {"candidates":[{"content":{"parts":[{"text":"Hello "}],"role":"model"},"index":0}]}

                data: {"candidates":[{"content":{"parts":[{"text":"there"}],"role":"model"},"index":0}]}

                data: {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}

                """;
        return new MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "text/event-stream");
    }

    @Override
    protected String expectedStreamedContent() {
        return "Hello there";
    }

    @Test
    void attachesTheApiKeyAsAQueryParameterNotAHeader() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new com.aether.gateway.core.domain.ChatCompletionRequest(
                "gemini-1.5-flash",
                java.util.List.of(new com.aether.gateway.core.domain.ChatMessage("user", "hi")),
                false));

        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(recorded.getPath()).contains("key=test-api-key");
        assertThat(recorded.getPath()).contains("gemini-1.5-flash:generateContent");
        assertThat(recorded.getHeader("Authorization")).isNull();
    }
}
