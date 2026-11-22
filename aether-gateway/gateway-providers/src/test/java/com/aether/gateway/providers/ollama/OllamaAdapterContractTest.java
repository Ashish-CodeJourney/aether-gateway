package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolsNotSupportedException;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
