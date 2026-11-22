package com.aether.gateway.providers.anthropic;

import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.providers.ProviderAdapterContractTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic's Messages API is the fourth genuinely distinct wire shape
 * here, and the one furthest from OpenAI's: authentication is
 * {@code x-api-key} plus a mandatory {@code anthropic-version} header
 * rather than a bearer token, a system prompt is a top-level string
 * rather than a message, assistant content is a list of typed blocks
 * rather than a string, {@code max_tokens} is required rather than
 * optional, and the stream is a sequence of named SSE event types
 * instead of one repeated chunk shape with a {@code [DONE]} sentinel.
 *
 * <p>Everything below runs against MockWebServer with fixtures in
 * Anthropic's real documented wire format. Nothing here has been run
 * against the live API - see the note in the adapter's javadoc.
 */
class AnthropicAdapterContractTest extends ProviderAdapterContractTest {

    @Override
    protected ProviderAdapter createAdapter(String baseUrl) {
        return new AnthropicAdapter(
                "anthropic-test", baseUrl, "test-api-key", 4096, RestClient.builder(), WebClient.builder());
    }

    @Override
    protected MockResponse successResponse() {
        return jsonResponse(200, """
                {
                  "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-sonnet-4-5",
                  "content": [{"type": "text", "text": "Hello there"}],
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {"input_tokens": 5, "output_tokens": 2}
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
                {"type": "error", "error": {"type": "rate_limit_error", "message": "Number of requests has exceeded your rate limit"}}
                """);
    }

    @Override
    protected MockResponse serverErrorResponse() {
        return jsonResponse(500, """
                {"type": "error", "error": {"type": "api_error", "message": "Internal server error"}}
                """);
    }

    @Override
    protected MockResponse streamingResponse() {
        String body = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"usage":{"input_tokens":5,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: ping
                data: {"type":"ping"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

                event: message_stop
                data: {"type":"message_stop"}

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

    private ChatCompletionRequest request(ChatMessage... messages) {
        return new ChatCompletionRequest("claude-sonnet-4-5", List.of(messages), false);
    }

    @Test
    void authenticatesWithAnApiKeyHeaderAndAPinnedApiVersion() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(request(new ChatMessage("user", "hi")));

        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-api-key");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(recorded.getHeader("Authorization")).isNull();
        assertThat(recorded.getPath()).endsWith("/messages");
    }

    @Test
    void liftsASystemMessageOutOfTheMessageListIntoTheTopLevelSystemField() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(request(
                new ChatMessage("system", "You are terse."),
                new ChatMessage("user", "hi")));

        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"system\":\"You are terse.\"");
        assertThat(body).doesNotContain("\"role\":\"system\"");
    }

    @Test
    void alwaysSendsMaxTokensBecauseTheApiRejectsARequestWithout() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(request(new ChatMessage("user", "hi")));

        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(body).contains("\"max_tokens\":4096");
    }

    @Test
    void reportsUsageFromTheApisOwnInputAndOutputTokenCounts() {
        server.enqueue(successResponse());

        var result = (ProviderResponse.Completion) adapter.invoke(request(new ChatMessage("user", "hi")));

        assertThat(result.response().usage().promptTokens()).isEqualTo(5);
        assertThat(result.response().usage().completionTokens()).isEqualTo(2);
    }

    @Test
    void normalisesStopReasonToTheOpenAiVocabularyTheDomainModelUses() {
        server.enqueue(jsonResponse(200, """
                {
                  "id": "msg_02", "type": "message", "role": "assistant", "model": "claude-sonnet-4-5",
                  "content": [{"type": "text", "text": "truncated"}],
                  "stop_reason": "max_tokens",
                  "usage": {"input_tokens": 5, "output_tokens": 4096}
                }
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(request(new ChatMessage("user", "hi")));

        assertThat(result.response().choices().get(0).finishReason()).isEqualTo("length");
    }

    @Test
    void treatsAnOverloadedResponseAsRetryable() {
        // 529 overloaded_error is Anthropic-specific and explicitly a
        // "try again" signal; a generic 4xx/5xx rule would strand it as
        // terminal and skip the failover this gateway exists to do.
        server.enqueue(jsonResponse(529, """
                {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
                """));

        var result = (ProviderResponse.ProviderError) adapter.invoke(request(new ChatMessage("user", "hi")));

        assertThat(result.retryable()).isTrue();
    }

    @Test
    void treatsAnInvalidApiKeyAsTerminalRatherThanRetryable() {
        // Retrying a 401 just burns the request budget across every
        // member of the chain and delays the real error reaching the
        // client.
        server.enqueue(jsonResponse(401, """
                {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}
                """));

        var result = (ProviderResponse.ProviderError) adapter.invoke(request(new ChatMessage("user", "hi")));

        assertThat(result.retryable()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(401);
    }

    @Test
    void ignoresTheStreamsNonContentEventsRatherThanEmittingEmptyChunksForThem() {
        // message_start, content_block_start, ping, content_block_stop
        // and message_stop all carry no text. Emitting a chunk per event
        // would pad the stream with empties the client has to filter.
        server.enqueue(streamingResponse());

        var chunks = reactor.adapter.JdkFlowAdapter
                .flowPublisherToFlux(adapter.invokeStreaming(request(new ChatMessage("user", "hi"))))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("Hello ");
        assertThat(chunks.get(1).deltaContent()).isEqualTo("there");
        assertThat(chunks.get(2).last()).isTrue();
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""";

    private static final String TIME_TOOL = """
            {"type":"function","function":{"name":"get_time"}}""";

    private ChatCompletionRequest toolRequest(String toolChoice, ChatMessage... messages) {
        return new ChatCompletionRequest("claude-sonnet-4-5", List.of(messages), false, null, List.of(WEATHER_TOOL, TIME_TOOL), toolChoice);
    }

    private tools.jackson.databind.JsonNode sentBody() throws InterruptedException {
        return JSON.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
    }

    @Test
    void translatesOpenAiToolDefinitionsIntoAnthropicToolsWithAnInputSchema() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(null, new ChatMessage("user", "hi")));

        assertThat(sentBody().get("tools")).isEqualTo(JSON.readTree("""
                [{"name":"get_weather","description":"Current weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}},
                 {"name":"get_time","input_schema":{"type":"object","properties":{}}}]
                """));
    }

    @Test
    void translatesEachOpenAiToolChoiceIntoItsAnthropicEquivalent() throws InterruptedException {
        var expectations = java.util.Map.of(
                "\"auto\"", "{\"type\":\"auto\"}",
                "\"required\"", "{\"type\":\"any\"}",
                "\"none\"", "{\"type\":\"none\"}",
                "{\"type\":\"function\",\"function\":{\"name\":\"get_time\"}}", "{\"type\":\"tool\",\"name\":\"get_time\"}");

        for (var expectation : expectations.entrySet()) {
            server.enqueue(successResponse());
            adapter.invoke(toolRequest(expectation.getKey(), new ChatMessage("user", "hi")));

            assertThat(sentBody().get("tool_choice")).as(expectation.getKey()).isEqualTo(JSON.readTree(expectation.getValue()));
        }
    }

    @Test
    void sendsNoToolFieldsForAPlainRequest() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(request(new ChatMessage("user", "hi")));

        var body = sentBody();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.get("messages").get(0)).isEqualTo(JSON.readTree("{\"role\":\"user\",\"content\":\"hi\"}"));
    }

    @Test
    void translatesAssistantToolCallsIntoToolUseBlocksAndMergesConsecutiveToolResultsIntoOneUserTurn() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(null,
                new ChatMessage("user", "Weather and time in Paris?"),
                new ChatMessage("assistant", "Checking.", List.of(
                        new ToolCall("toolu_1", "function", "get_weather", "{\"city\":\"Paris\"}"),
                        new ToolCall("toolu_2", "function", "get_time", "{}")), null),
                new ChatMessage("tool", "18C", List.of(), "toolu_1"),
                new ChatMessage("tool", "14:00", List.of(), "toolu_2")));

        var messages = sentBody().get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1)).isEqualTo(JSON.readTree("""
                {"role":"assistant","content":[
                  {"type":"text","text":"Checking."},
                  {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}},
                  {"type":"tool_use","id":"toolu_2","name":"get_time","input":{}}]}
                """));
        assertThat(messages.get(2)).isEqualTo(JSON.readTree("""
                {"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"toolu_1","content":"18C"},
                  {"type":"tool_result","tool_use_id":"toolu_2","content":"14:00"}]}
                """));
    }

    @Test
    void returnsToolUseBlocksAsOpenAiToolCalls() {
        server.enqueue(jsonResponse(200, """
                {
                  "id": "msg_tool", "type": "message", "role": "assistant", "model": "claude-sonnet-4-5",
                  "content": [
                    {"type": "text", "text": "Let me check."},
                    {"type": "tool_use", "id": "toolu_01A09q90qw90lq917835lq9", "name": "get_weather", "input": {"city": "Paris"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 300, "output_tokens": 40}
                }
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(null, new ChatMessage("user", "Weather?")));

        var choice = result.response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        assertThat(choice.message().content()).isEqualTo("Let me check.");
        assertThat(choice.message().toolCalls()).hasSize(1);
        var call = choice.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("toolu_01A09q90qw90lq917835lq9");
        assertThat(call.functionName()).isEqualTo("get_weather");
        assertThat(JSON.readTree(call.argumentsJson())).isEqualTo(JSON.readTree("{\"city\":\"Paris\"}"));
    }

    @Test
    void streamsToolUseAsOpenAiToolCallFragmentsIndexedAmongToolCallsOnly() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"usage":{"input_tokens":5,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" \\"Paris\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}

                event: message_stop
                data: {"type":"message_stop"}

                """));

        var chunks = reactor.adapter.JdkFlowAdapter
                .flowPublisherToFlux(adapter.invokeStreaming(toolRequest(null, new ChatMessage("user", "Weather?"))))
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("Checking.");
        assertThat(chunks.get(1).toolCallDeltas()).containsExactly(new ToolCallDelta(0, "toolu_01", "get_weather", ""));
        assertThat(chunks.get(2).toolCallDeltas()).containsExactly(new ToolCallDelta(0, null, null, "{\"city\":"));
        assertThat(chunks.get(3).toolCallDeltas()).containsExactly(new ToolCallDelta(0, null, null, " \"Paris\"}"));
        assertThat(chunks.get(4).last()).isTrue();
        assertThat(chunks.get(4).finishReason()).isEqualTo("tool_calls");
    }
}
