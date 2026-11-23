package com.aether.gateway.providers.gemini;

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
 * Fixtures follow the Gemini API's {@code generateContent} /
 * {@code streamGenerateContent?alt=sse} wire format (camelCase):
 * {@code functionDeclarations} inside {@code tools},
 * {@code toolConfig.functionCallingConfig}, and {@code functionCall} /
 * {@code functionResponse} parts, per the API reference's
 * FunctionDeclaration, FunctionCall, FunctionResponse, ToolConfig and Part
 * definitions.
 */
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

        adapter.invoke(new ChatCompletionRequest("gemini-1.5-flash", List.of(new ChatMessage("user", "hi")), false));

        var recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded.getPath()).contains("key=test-api-key");
        assertThat(recorded.getPath()).contains("gemini-1.5-flash:generateContent");
        assertThat(recorded.getHeader("Authorization")).isNull();
    }

    @Test
    void reportsTheLatestRunningUsageMetadataOnlyOnceTheStreamFinishes() {
        // Gemini repeats a running usageMetadata on every chunk; only the
        // final, complete count belongs to the response.
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":1,"totalTokenCount":9}}

                data: {"candidates":[{"content":{"parts":[{"text":" you"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3,"totalTokenCount":11}}

                """));

        var chunks = streamed(new ChatCompletionRequest("test-model", List.of(new ChatMessage("user", "hi")), true));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).usage()).isNull();
        assertThat(chunks.get(1).usage()).isEqualTo(Usage.of(8, 3));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String WEATHER_TOOL = """
            {"type":"function","function":{"name":"get_weather","description":"Current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""";

    private static final String TIME_TOOL = """
            {"type":"function","function":{"name":"get_time"}}""";

    private static ChatCompletionRequest toolRequest(boolean stream, String toolChoice, ChatMessage... messages) {
        return new ChatCompletionRequest("gemini-2.5-flash", List.of(messages), stream, null, List.of(WEATHER_TOOL, TIME_TOOL), toolChoice);
    }

    private static ChatCompletionRequest singleToolRequest(String tool) {
        return new ChatCompletionRequest("gemini-2.5-flash", List.of(new ChatMessage("user", "hi")), false, null, List.of(tool), null);
    }

    private JsonNode sentBody() throws InterruptedException {
        return JSON.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
    }

    private List<ProviderResponse.StreamChunk> streamed(ChatCompletionRequest request) {
        return JdkFlowAdapter.flowPublisherToFlux(adapter.invokeStreaming(request))
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    @Test
    void translatesOpenAiToolDefinitionsIntoGeminiFunctionDeclarations() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, null, new ChatMessage("user", "hi")));

        JsonNode body = sentBody();
        assertThat(body.get("tools")).isEqualTo(JSON.readTree("""
                [{"functionDeclarations":[
                  {"name":"get_weather","description":"Current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}},
                  {"name":"get_time"}]}]
                """));
        assertThat(body.has("toolConfig")).isFalse();
    }

    @Test
    void convertsJsonSchemaKeywordsOutsideGeminisOpenApiSchemaSubset() throws InterruptedException {
        server.enqueue(successResponse());
        String strictTool = """
                {"type":"function","function":{"name":"search","parameters":{
                  "$schema":"http://json-schema.org/draft-07/schema#",
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{
                    "query":{"type":"string","minLength":1,"examples":["cats"]},
                    "unit":{"const":"celsius"},
                    "limit":{"type":["integer","null"],"minimum":1},
                    "filters":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{"field":{"type":"string"}}}},
                    "mode":{"oneOf":[{"type":"string"},{"type":"number"}]},
                    "additionalProperties":{"type":"string","description":"a property that happens to share a keyword's name"}
                  },
                  "required":["query"]}}}""";

        adapter.invoke(singleToolRequest(strictTool));

        assertThat(sentBody().at("/tools/0/functionDeclarations/0/parameters")).isEqualTo(JSON.readTree("""
                {"type":"object",
                 "properties":{
                   "query":{"type":"string","minLength":1},
                   "unit":{"enum":["celsius"]},
                   "limit":{"type":"integer","nullable":true,"minimum":1},
                   "filters":{"type":"array","items":{"type":"object","properties":{"field":{"type":"string"}}}},
                   "mode":{"anyOf":[{"type":"string"},{"type":"number"}]},
                   "additionalProperties":{"type":"string","description":"a property that happens to share a keyword's name"}
                 },
                 "required":["query"]}
                """));
    }

    @Test
    void omitsTheParametersOfAFunctionThatTakesNoArgumentsBecauseGeminiRejectsAnEmptyObjectSchema() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(singleToolRequest("""
                {"type":"function","function":{"name":"get_time","parameters":{"type":"object","properties":{}}}}"""));

        assertThat(sentBody().at("/tools/0/functionDeclarations/0")).isEqualTo(JSON.readTree("""
                {"name":"get_time"}"""));
    }

    @Test
    void translatesEachOpenAiToolChoiceIntoAGeminiFunctionCallingConfig() throws InterruptedException {
        var expectations = java.util.Map.of(
                "\"auto\"", "{\"mode\":\"AUTO\"}",
                "\"none\"", "{\"mode\":\"NONE\"}",
                "\"required\"", "{\"mode\":\"ANY\"}",
                "{\"type\":\"function\",\"function\":{\"name\":\"get_time\"}}", "{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"get_time\"]}");

        for (var expectation : expectations.entrySet()) {
            server.enqueue(successResponse());
            adapter.invoke(toolRequest(false, expectation.getKey(), new ChatMessage("user", "hi")));

            assertThat(sentBody().at("/toolConfig/functionCallingConfig")).as(expectation.getKey())
                    .isEqualTo(JSON.readTree(expectation.getValue()));
        }
    }

    @Test
    void sendsNoToolFieldsOrNullsForAPlainRequest() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(new ChatCompletionRequest("gemini-2.5-flash", List.of(new ChatMessage("user", "hi")), false));

        assertThat(sentBody()).isEqualTo(JSON.readTree("""
                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}
                """));
    }

    @Test
    void replaysAssistantToolCallsAsFunctionCallPartsAndToolResultsAsOneMergedFunctionResponseTurn() throws InterruptedException {
        server.enqueue(successResponse());

        adapter.invoke(toolRequest(false, null,
                new ChatMessage("user", "Weather and time in Paris?"),
                new ChatMessage("assistant", "Checking.", List.of(
                        new ToolCall("call_a", "function", "get_weather", "{\"city\":\"Paris\"}"),
                        new ToolCall("call_b", "function", "get_time", "")), null),
                new ChatMessage("tool", "{\"temperature\":18}", List.of(), "call_a"),
                new ChatMessage("tool", "14:00", List.of(), "call_b")));

        assertThat(sentBody().get("contents")).isEqualTo(JSON.readTree("""
                [
                  {"role":"user","parts":[{"text":"Weather and time in Paris?"}]},
                  {"role":"model","parts":[
                    {"text":"Checking."},
                    {"functionCall":{"name":"get_weather","args":{"city":"Paris"}},"thoughtSignature":"skip_thought_signature_validator"},
                    {"functionCall":{"name":"get_time","args":{}}}]},
                  {"role":"user","parts":[
                    {"functionResponse":{"name":"get_weather","response":{"temperature":18}}},
                    {"functionResponse":{"name":"get_time","response":{"output":"14:00"}}}]}
                ]
                """));
    }

    @Test
    void rejectsAToolResultWhoseCallIdMatchesNoEarlierAssistantToolCall() {
        var request = new ChatCompletionRequest("gemini-2.5-flash", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("tool", "18C", List.of(), "call_unknown")), false);

        var result = adapter.invoke(request);

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        var error = (ProviderResponse.ProviderError) result;
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.retryable()).isFalse();
        assertThat(error.message()).contains("call_unknown");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void returnsFunctionCallPartsAsOpenAiToolCallsWithGeneratedStableIds() {
        server.enqueue(jsonResponse(200, """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {"functionCall": {"name": "get_weather", "args": {"city": "Paris"}}},
                          {"functionCall": {"name": "get_time", "args": {}}}
                        ]
                      },
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ],
                  "usageMetadata": {"promptTokenCount": 30, "candidatesTokenCount": 12, "totalTokenCount": 42}
                }
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(false, null, new ChatMessage("user", "Weather?")));

        var choice = result.response().choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        assertThat(choice.message().content()).isEmpty();
        assertThat(choice.message().toolCalls()).containsExactly(
                new ToolCall("call_0_get_weather", "function", "get_weather", "{\"city\":\"Paris\"}"),
                new ToolCall("call_1_get_time", "function", "get_time", "{}"));
        assertThat(result.response().usage()).isEqualTo(Usage.of(30, 12));
    }

    @Test
    void keepsAFunctionCallIdWhenGeminiSuppliesOne() {
        server.enqueue(jsonResponse(200, """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"id":"fc-123","name":"get_weather","args":{"city":"Paris"}},"thoughtSignature":"c2lnbmF0dXJl"}]},
                  "finishReason":"STOP","index":0}]}
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(toolRequest(false, null, new ChatMessage("user", "Weather?")));

        assertThat(result.response().choices().get(0).message().toolCalls().get(0).id()).isEqualTo("fc-123");
    }

    @Test
    void joinsEveryTextPartOfAReplyRatherThanOnlyTheFirst() {
        server.enqueue(jsonResponse(200, """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"Hello "},{"text":"there"}]},"finishReason":"STOP","index":0}]}
                """));

        var result = (ProviderResponse.Completion) adapter.invoke(
                new ChatCompletionRequest("gemini-2.5-flash", List.of(new ChatMessage("user", "hi")), false));

        assertThat(result.response().choices().get(0).message().content()).isEqualTo("Hello there");
        assertThat(result.response().choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void streamsEachFunctionCallAsOneCompleteToolCallDeltaAndFinishesWithToolCalls() {
        server.enqueue(sseResponse("""
                data: {"candidates":[{"content":{"parts":[{"text":"Let me check."}],"role":"model"},"index":0}]}

                data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}},{"functionCall":{"name":"get_time","args":{}}}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":12,"totalTokenCount":42}}

                """));

        var chunks = streamed(toolRequest(true, null, new ChatMessage("user", "Weather?")));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).deltaContent()).isEqualTo("Let me check.");
        assertThat(chunks.get(0).toolCallDeltas()).isEmpty();
        assertThat(chunks.get(1).deltaContent()).isEmpty();
        assertThat(chunks.get(1).toolCallDeltas()).containsExactly(
                new ToolCallDelta(0, "call_0_get_weather", "get_weather", "{\"city\":\"Paris\"}"),
                new ToolCallDelta(1, "call_1_get_time", "get_time", "{}"));
        assertThat(chunks.get(1).last()).isTrue();
        assertThat(chunks.get(1).finishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(1).usage()).isEqualTo(Usage.of(30, 12));
    }

    @Test
    void numbersStreamedFunctionCallsAcrossChunksSoALaterCallDoesNotReuseAnIndex() {
        server.enqueue(sseResponse("""
                data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}],"role":"model"},"index":0}]}

                data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_time","args":{}}}],"role":"model"},"finishReason":"STOP","index":0}]}

                """));

        var chunks = streamed(toolRequest(true, null, new ChatMessage("user", "Weather?")));

        assertThat(chunks.get(0).toolCallDeltas())
                .containsExactly(new ToolCallDelta(0, "call_0_get_weather", "get_weather", "{\"city\":\"Paris\"}"));
        assertThat(chunks.get(1).toolCallDeltas()).containsExactly(new ToolCallDelta(1, "call_1_get_time", "get_time", "{}"));
    }

    @Test
    void leavesTheFinishReasonToTheCallerWhenAStreamEndsWithoutAFunctionCall() {
        server.enqueue(streamingResponse());

        var chunks = streamed(new ChatCompletionRequest("gemini-2.5-flash", List.of(new ChatMessage("user", "hi")), true));

        assertThat(chunks.getLast().finishReason()).isNull();
    }

    @Test
    void sendsToolsOnTheStreamingRequestToo() throws InterruptedException {
        server.enqueue(streamingResponse());

        streamed(toolRequest(true, "\"required\"", new ChatMessage("user", "Weather?")));

        JsonNode body = sentBody();
        assertThat(body.at("/tools/0/functionDeclarations/0/name").asString()).isEqualTo("get_weather");
        assertThat(body.at("/toolConfig/functionCallingConfig/mode").asString()).isEqualTo("ANY");
    }

    @Test
    void rejectsAnUnmatchedToolResultOnTheStreamingPathAsAClientErrorWithoutCallingGemini() {
        var request = new ChatCompletionRequest("gemini-2.5-flash", List.of(
                new ChatMessage("user", "weather?"),
                new ChatMessage("tool", "18C", List.of(), "call_unknown")), true);

        assertThatThrownBy(() -> streamed(request))
                .isInstanceOf(ProviderStreamException.class)
                .hasMessageContaining("call_unknown")
                .satisfies(e -> {
                    assertThat(((ProviderStreamException) e).httpStatus()).isEqualTo(400);
                    assertThat(((ProviderStreamException) e).retryable()).isFalse();
                });
        assertThat(server.getRequestCount()).isZero();
    }
}
