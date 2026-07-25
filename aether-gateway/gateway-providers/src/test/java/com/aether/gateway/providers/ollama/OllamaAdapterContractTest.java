package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

class OllamaAdapterContractTest extends ProviderAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new OllamaAdapter("ollama-test", baseUrl, null, RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse successResponse() {
        return jsonResponse(200, """
                {
                  "model": "llama3",
                  "created_at": "2024-01-01T00:00:00Z",
                  "message": {"role": "assistant", "content": "Hello there"},
                  "done": true,
                  "prompt_eval_count": 5,
                  "eval_count": 2
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
                {"error": "rate limit exceeded"}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": "internal server error"}
                """);
    }

    @Override
    protected MockResponse streamingResponse() {
        String body = """
                {"model":"llama3","created_at":"2024-01-01T00:00:00Z","message":{"role":"assistant","content":"Hello "},"done":false}
                {"model":"llama3","created_at":"2024-01-01T00:00:00Z","message":{"role":"assistant","content":"there"},"done":false}
                {"model":"llama3","created_at":"2024-01-01T00:00:00Z","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":5,"eval_count":2}
                """;
        return new MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .setHeader("Content-Type", "application/x-ndjson");
    }

    @Override
    protected String expectedStreamedContent() {
        return "Hello there";
    }
}
