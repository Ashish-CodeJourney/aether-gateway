package com.aether.gateway.providers.openai;

import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleAdapterContractTest extends ProviderAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new OpenAiCompatibleAdapter(
                "openai-compatible-test", baseUrl, "test-api-key", RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse successResponse() {
        return jsonResponse(200, """
                {
                  "id": "chatcmpl-abc123",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "test-model",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "Hello there"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
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
                {"error": {"message": "Rate limit reached", "type": "rate_limit_error"}}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"error": {"message": "Internal server error", "type": "server_error"}}
                """);
    }

    @Override
    protected MockResponse streamingResponse() {
        String body = """
                data:{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model","choices":[{"index":0,"delta":{"content":"Hello "},"finish_reason":null}]}

                data:{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model","choices":[{"index":0,"delta":{"content":"there"},"finish_reason":null}]}

                data:{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

                data:[DONE]

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
    void attachesABearerAuthorizationHeaderWhenAnApiKeyIsConfigured() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new com.aether.gateway.core.domain.ChatCompletionRequest(
                "test-model",
                java.util.List.of(new com.aether.gateway.core.domain.ChatMessage("user", "hi")),
                false));

        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
    }

    @Test
    void omitsTheAuthorizationHeaderWhenNoApiKeyIsConfigured() throws InterruptedException {
        ProviderAdapter noKeyAdapter = new OpenAiCompatibleAdapter(
                "openai-compatible-test", server.url("/").toString(), null, RestClient.builder(), WebClient.builder());
        server.enqueue(successResponse());

        noKeyAdapter.invoke(new com.aether.gateway.core.domain.ChatCompletionRequest(
                "test-model",
                java.util.List.of(new com.aether.gateway.core.domain.ChatMessage("user", "hi")),
                false));

        var recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(recorded.getHeader("Authorization")).isNull();
    }
}
