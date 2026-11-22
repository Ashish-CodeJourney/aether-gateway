package com.aether.gateway.providers.openai;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]},"strict":true}}""";

    private static ChatCompletionRequest toolConversation(boolean stream) {
        var call = new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}");
        return new ChatCompletionRequest("test-model", List.of(
                new ChatMessage("user", "Weather in Paris?"),
                new ChatMessage("assistant", "", List.of(call), null),
                new ChatMessage("tool", "18C and sunny", List.of(), "call_1")),
                stream, null, List.of(WEATHER_TOOL), "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}");
    }

    private tools.jackson.databind.JsonNode sentBody() throws InterruptedException {
        return JSON.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
    }

    @Test
    void passesToolDefinitionsAndToolChoiceThroughVerbatim() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolConversation(false));

        var body = sentBody();
        assertThat(body.get("tools")).isEqualTo(JSON.readTree("[" + WEATHER_TOOL + "]"));
        assertThat(body.get("tool_choice")).isEqualTo(JSON.readTree("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}"));
    }

    @Test
    void sendsAssistantToolCallsWithNullContentAndToolResultsWithTheirCallId() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolConversation(false));

        var messages = sentBody().get("messages");
        assertThat(messages.get(1)).isEqualTo(JSON.readTree("""
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}]}
                """));
        assertThat(messages.get(2)).isEqualTo(JSON.readTree("""
                {"role":"tool","content":"18C and sunny","tool_call_id":"call_1"}
                """));
    }

    @Test
    void sendsNoToolFieldsForAPlainRequest() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hi")), false));

        var body = sentBody();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.get("messages").get(0)).isEqualTo(JSON.readTree("{\"role\":\"user\",\"content\":\"hi\"}"));
    }

    @Test
    void returnsTheAssistantsToolCallsAndToolCallsFinishReason() {
        server.enqueue(jsonResponse(200, """
                {
                  "id": "chatcmpl-tool", "object": "chat.completion", "created": 1700000000, "model": "test-model",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant", "content": null,
                      "tool_calls": [{"id": "call_abc", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\": \\"Paris\\"}"}}]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}
                }
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolConversation(false));

        var choice = result.response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        assertThat(choice.message().content()).isEmpty();
        assertThat(choice.message().toolCalls())
                .containsExactly(new ToolCall("call_abc", "function", "get_weather", "{\"city\": \"Paris\"}"));
    }

    @Test
    void relaysStreamedToolCallFragmentsAndTheFinishReason() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data:{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

                        data:{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

                        data:{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Paris\\"}"}}]},"finish_reason":null}]}

                        data:{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                        data:[DONE]

                        """));

        var chunks = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(toolConversation(true)))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).toolCallDeltas()).containsExactly(new ToolCallDelta(0, "call_abc", "get_weather", ""));
        assertThat(chunks.get(1).toolCallDeltas()).containsExactly(new ToolCallDelta(0, null, null, "{\"city\":"));
        assertThat(chunks.get(2).toolCallDeltas()).containsExactly(new ToolCallDelta(0, null, null, "\"Paris\"}"));
        assertThat(chunks.get(3).last()).isTrue();
        assertThat(chunks.get(3).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void sendsTheSameSnakeCaseToolFieldsOnTheStreamingPath() throws InterruptedException {
        server.enqueue(streamingResponse());

        reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(toolConversation(true)))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        var body = sentBody();
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.has("tool_choice")).isTrue();
        assertThat(body.get("messages").get(1).has("tool_calls")).isTrue();
        assertThat(body.get("messages").get(2).get("tool_call_id").asString()).isEqualTo("call_1");
    }
}
