package com.aether.gateway.providers.ollama;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.adapter.JdkFlowAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tool-calling fixtures are trimmed copies of real {@code /api/chat}
 * responses captured from Ollama 0.24: {@code message.tool_calls} items
 * carry an {@code id} and {@code function.index}, {@code arguments} is a
 * JSON object rather than a string, and a streamed call arrives whole in
 * one {@code done:false} line followed by a {@code done:true} line whose
 * {@code done_reason} is still "stop". The request side follows Ollama's
 * tool-calling docs: OpenAI-like {@code tools}, and a tool result sent as
 * {@code role: "tool"} with {@code tool_name}.
 */
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
        return ndjsonResponse(body);
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
    void reportsNoUsageWhenTheFinalLineCarriesNoCounts() {
        server.enqueue(ndjsonResponse("""
                {"model":"llama3","message":{"role":"assistant","content":"Hi"},"done":false}
                {"model":"llama3","message":{"role":"assistant","content":""},"done":true}
                """));

        var chunks = streamed(new ChatCompletionRequest("llama3", List.of(new ChatMessage("user", "hi")), true));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).usage()).isNull();
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""";

    private static MockResponse ndjsonResponse(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-ndjson").setBody(body);
    }

    private static ChatCompletionRequest toolRequest(boolean stream, String toolChoice, ChatMessage... messages) {
        return new ChatCompletionRequest("llama3.1", List.of(messages), stream, null, List.of(WEATHER_TOOL), toolChoice);
    }

    private JsonNode sentBody() throws InterruptedException {
        return JSON.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
    }

    private List<ProviderResponse.StreamChunk> streamed(ChatCompletionRequest request) {
        return JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(request))
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private static MockResponse toolCallResponse() {
        return jsonResponse(200, """
                {"model":"llama3.1","created_at":"2026-11-23T06:05:23.066022829Z",
                 "message":{"role":"assistant","content":"","tool_calls":[
                   {"id":"call_qit93b6g","function":{"index":0,"name":"get_weather","arguments":{"city":"Paris"}}}]},
                 "done":true,"done_reason":"stop","prompt_eval_count":66,"eval_count":15}
                """);
    }

    @Test
    void sendsOpenAiToolDefinitionsToOllamaUnchanged() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, null, new ChatMessage("user", "hi")));

        assertThat(sentBody().get("tools")).isEqualTo(JSON.readTree("[" + WEATHER_TOOL + "]"));
    }

    @Test
    void sendsNoToolFieldsForAPlainRequest() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new ChatCompletionRequest("llama3", List.of(new ChatMessage("user", "hi")), false));

        assertThat(sentBody()).isEqualTo(JSON.readTree("""
                {"model":"llama3","messages":[{"role":"user","content":"hi"}],"stream":false}
                """));
    }

    @Test
    void sendsToolsForToolChoiceAuto() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, "\"auto\"", new ChatMessage("user", "hi")));

        assertThat(sentBody().get("tools")).hasSize(1);
    }

    @Test
    void honoursToolChoiceNoneByNotOfferingTheToolsAtAll() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, "\"none\"", new ChatMessage("user", "hi")));

        assertThat(sentBody().has("tools")).isFalse();
    }

    @Test
    void refusesAToolChoiceOllamaCannotEnforceRatherThanSilentlyIgnoringIt() {
        for (String choice : List.of("\"required\"", "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}")) {
            var result = adapter.invoke(toolRequest(false, choice, new ChatMessage("user", "hi")));

            assertThat(result).as(choice).isInstanceOf(ProviderResponse.ProviderError.class);
            var error = (ProviderResponse.ProviderError) result;
            assertThat(error.errorCode()).isEqualTo("tool_choice_not_supported");
            assertThat(error.httpStatus()).isEqualTo(400);
            assertThat(error.retryable()).isFalse();
            assertThat(error.message()).contains(adapter.providerName());
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesAnUnenforceableToolChoiceOnTheStreamingPathWithTheSameClientError() {
        assertThatThrownBy(() -> streamed(toolRequest(true, "\"required\"", new ChatMessage("user", "hi"))))
                .isInstanceOf(ProviderStreamException.class)
                .satisfies(e -> {
                    var failure = (ProviderStreamException) e;
                    assertThat(failure.errorCode()).isEqualTo("tool_choice_not_supported");
                    assertThat(failure.httpStatus()).isEqualTo(400);
                    assertThat(failure.retryable()).isFalse();
                });
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void replaysAssistantToolCallsWithObjectArgumentsAndToolResultsWithTheirFunctionName() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, null,
                new ChatMessage("user", "Weather in Paris?"),
                new ChatMessage("assistant", "", List.of(
                        new ToolCall("call_a", "function", "get_weather", "{\"city\":\"Paris\"}")), null),
                new ChatMessage("tool", "18C", List.of(), "call_a")));

        assertThat(sentBody().get("messages")).isEqualTo(JSON.readTree("""
                [
                  {"role":"user","content":"Weather in Paris?"},
                  {"role":"assistant","content":"","tool_calls":[
                    {"id":"call_a","type":"function","function":{"name":"get_weather","arguments":{"city":"Paris"}}}]},
                  {"role":"tool","content":"18C","tool_name":"get_weather","tool_call_id":"call_a"}
                ]
                """));
    }

    @Test
    void sendsAToolResultWithoutAFunctionNameWhenNoEarlierCallMatchesItsId() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new ChatCompletionRequest("llama3.1", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("tool", "18C", List.of(), "call_unknown")), false));

        assertThat(sentBody().at("/messages/1")).isEqualTo(JSON.readTree("""
                {"role":"tool","content":"18C","tool_call_id":"call_unknown"}
                """));
    }

    @Test
    void returnsToolCallsWithTheirArgumentsObjectAsTheJsonStringOpenAiClientsExpect() {
        server.enqueue(toolCallResponse());

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(false, null, new ChatMessage("user", "Weather?")));

        var choice = result.response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        assertThat(choice.message().role()).isEqualTo("assistant");
        assertThat(choice.message().toolCalls())
                .containsExactly(new ToolCall("call_qit93b6g", "function", "get_weather", "{\"city\":\"Paris\"}"));
        assertThat(result.response().usage()).isEqualTo(Usage.of(66, 15));
    }

    @Test
    void generatesStableIdsForToolCallsFromOllamaVersionsThatSendNone() {
        server.enqueue(jsonResponse(200, """
                {"model":"llama3.1","message":{"role":"assistant","content":"","tool_calls":[
                   {"function":{"name":"get_weather","arguments":{"city":"Paris"}}},
                   {"function":{"name":"get_weather","arguments":{"city":"Rome"}}}]},
                 "done":true,"done_reason":"stop"}
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(false, null, new ChatMessage("user", "Weather?")));

        assertThat(result.response().choices().get(0).message().toolCalls())
                .extracting(ToolCall::id)
                .containsExactly("call_0_get_weather", "call_1_get_weather");
    }

    @Test
    void streamsAToolCallAsOneCompleteDeltaAndFinishesWithToolCalls() {
        server.enqueue(ndjsonResponse("""
                {"model":"llama3.1","created_at":"2026-11-23T06:05:25.95069697Z","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_hlqmoxba","function":{"index":0,"name":"get_weather","arguments":{"city":"Paris"}}}]},"done":false}
                {"model":"llama3.1","created_at":"2026-11-23T06:05:26.062224233Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":66,"eval_count":15}
                """));

        var chunks = streamed(toolRequest(true, null, new ChatMessage("user", "Weather?")));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).toolCallDeltas())
                .containsExactly(new ToolCallDelta(0, "call_hlqmoxba", "get_weather", "{\"city\":\"Paris\"}"));
        assertThat(chunks.get(0).last()).isFalse();
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).finishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(1).usage()).isEqualTo(Usage.of(66, 15));
    }

    @Test
    void numbersStreamedToolCallsAcrossLinesAndGeneratesIdsWhenAbsent() {
        server.enqueue(ndjsonResponse("""
                {"model":"llama3.1","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Paris"}}}]},"done":false}
                {"model":"llama3.1","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"Rome"}}}]},"done":false}
                {"model":"llama3.1","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """));

        var chunks = streamed(toolRequest(true, null, new ChatMessage("user", "Weather?")));

        assertThat(chunks.get(0).toolCallDeltas())
                .containsExactly(new ToolCallDelta(0, "call_0_get_weather", "get_weather", "{\"city\":\"Paris\"}"));
        assertThat(chunks.get(1).toolCallDeltas())
                .containsExactly(new ToolCallDelta(1, "call_1_get_weather", "get_weather", "{\"city\":\"Rome\"}"));
    }

    @Test
    void leavesTheFinishReasonToTheCallerWhenAStreamEndsWithoutAToolCall() {
        server.enqueue(streamingResponse());

        var chunks = streamed(new ChatCompletionRequest("llama3", List.of(new ChatMessage("user", "hi")), true));

        assertThat(chunks.getLast().finishReason()).isNull();
    }

    @Test
    void sendsToolsAndToolMessagesInSnakeCaseOnTheStreamingRequestToo() throws InterruptedException {
        server.enqueue(streamingResponse());

        streamed(toolRequest(true, null,
                new ChatMessage("user", "Weather in Paris?"),
                new ChatMessage("assistant", "", List.of(new ToolCall("call_a", "function", "get_weather", "{}")), null),
                new ChatMessage("tool", "18C", List.of(), "call_a")));

        JsonNode body = sentBody();
        assertThat(body.at("/tools/0/function/name").asString()).isEqualTo("get_weather");
        assertThat(body.at("/messages/2/tool_name").asString()).isEqualTo("get_weather");
        assertThat(body.at("/messages/1/tool_calls/0/function/arguments").isObject()).isTrue();
    }
}
