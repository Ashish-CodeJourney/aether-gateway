package com.aether.gateway.providers.mock;

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
import reactor.adapter.JdkFlowAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock provider speaks the OpenAI wire shape at {@code /v1/...}; the
 * tool-calling tests here are what let the acceptance suite drive tool
 * calls end to end through it.
 */
class MockProviderAdapterContractTest extends ProviderAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}""";

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new MockProviderAdapter("mock-test", baseUrl, RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse successResponse() {
        return jsonResponse(200, """
                {"id":"mock-1","object":"chat.completion","created":1700000000,"model":"mock",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Mock response for model mock"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":12,"total_tokens":13}}
                """);
    }

    @Override
    protected String expectedSuccessContent() {
        return "Mock response for model mock";
    }

    @Override
    protected MockResponse rateLimitResponse() {
        return jsonResponse(429, "{\"title\":\"rate_limited\"}");
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, "{\"title\":\"internal_error\"}");
    }

    @Override
    protected MockResponse streamingResponse() {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{"content":"Mock "},"finish_reason":null}]}

                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{"content":"stream"},"finish_reason":null}]}

                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

                data:[DONE]

                """);
    }

    @Override
    protected String expectedStreamedContent() {
        return "Mock stream";
    }

    private static ChatCompletionRequest toolRequest(boolean stream) {
        return new ChatCompletionRequest("mock", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("assistant", "", List.of(new ToolCall("call_1", "function", "get_weather", "{}")), null),
                new ChatMessage("tool", "18C", List.of(), "call_1")),
                stream, null, List.of(WEATHER_TOOL), "\"auto\"");
    }

    @Test
    void forwardsToolPayloadsInSnakeCaseOnBothPaths() throws InterruptedException {
        server.enqueue(successResponse());
        server.enqueue(streamingResponse());

        adapter.invoke(toolRequest(false));
        JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(toolRequest(true))).collectList().block(Duration.ofSeconds(10));

        for (int i = 0; i < 2; i++) {
            JsonNode body = JSON.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
            assertThat(body.get("tools")).isEqualTo(JSON.readTree("[" + WEATHER_TOOL + "]"));
            assertThat(body.get("tool_choice").asString()).isEqualTo("auto");
            assertThat(body.get("messages").get(1).get("tool_calls").get(0).get("id").asString()).isEqualTo("call_1");
            assertThat(body.get("messages").get(2).get("tool_call_id").asString()).isEqualTo("call_1");
        }
    }

    @Test
    void returnsTheMockProvidersToolCall() {
        server.enqueue(jsonResponse(200, """
                {"id":"mock-2","object":"chat.completion","created":1,"model":"mock",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_mock_0","type":"function","function":{"name":"get_weather","arguments":"{\\"mock\\":true}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":12,"total_tokens":13}}
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(false));

        assertThat(result.response().choices().get(0).message().toolCalls())
                .containsExactly(new ToolCall("call_mock_0", "function", "get_weather", "{\"mock\":true}"));
        assertThat(result.response().choices().get(0).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void relaysTheMockProvidersStreamedToolCall() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_mock_0","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}

                data:{"id":"m","object":"chat.completion.chunk","created":1,"model":"mock","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data:[DONE]

                """));

        var chunks = JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(toolRequest(true))).collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).toolCallDeltas()).containsExactly(new ToolCallDelta(0, "call_mock_0", "get_weather", ""));
        assertThat(chunks.get(1).toolCallDeltas()).containsExactly(new ToolCallDelta(0, null, null, "{}"));
        assertThat(chunks.get(2).finishReason()).isEqualTo("tool_calls");
    }
}
