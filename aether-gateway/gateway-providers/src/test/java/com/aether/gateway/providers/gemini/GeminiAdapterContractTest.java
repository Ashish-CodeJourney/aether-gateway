package com.aether.gateway.providers.gemini;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolsNotSupportedException;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Override
    protected Usage expectedStreamedUsage() {
        return Usage.of(5, 2);
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

    private static ChatCompletionRequest requestWithTools(boolean stream) {
        return new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "weather?")), stream, null,
                List.of("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}"), null);
    }

    @Test
    void refusesToolDefinitionsWithAClearTerminalErrorInsteadOfSilentlyDroppingThem() {
        var result = adapter.invoke(requestWithTools(false));

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        var error = (ProviderResponse.ProviderError) result;
        assertThat(error.errorCode()).isEqualTo("tools_not_supported");
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.retryable()).isFalse();
        assertThat(error.message()).contains(adapter.providerName());
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesAConversationCarryingToolResultsEvenWithoutToolDefinitions() {
        var request = new ChatCompletionRequest("test-model", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("tool", "18C", List.of(), "call_1")), false);

        var result = adapter.invoke(request);

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        assertThat(((ProviderResponse.ProviderError) result).errorCode()).isEqualTo("tools_not_supported");
    }

    @Test
    void refusesToolsOnTheStreamingPathBeforeAnyStreamStarts() {
        assertThatThrownBy(() -> adapter.invokeStreaming(requestWithTools(true)))
                .isInstanceOf(ToolsNotSupportedException.class)
                .hasMessageContaining(adapter.providerName());
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void reportsTheLatestRunningUsageMetadataOnlyOnceTheStreamFinishes() {
        // Gemini repeats a running usageMetadata on every chunk; only the
        // final, complete count belongs to the response.
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":1,"totalTokenCount":9}}

                data: {"candidates":[{"content":{"parts":[{"text":" you"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3,"totalTokenCount":11}}

                """));

        var chunks = reactor.adapter.JdkFlowAdapter
                .flowPublisherToFlux(adapter.invokeStreaming(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hi")), true)))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).usage()).isNull();
        assertThat(chunks.get(1).usage()).isEqualTo(Usage.of(8, 3));
    }
}
