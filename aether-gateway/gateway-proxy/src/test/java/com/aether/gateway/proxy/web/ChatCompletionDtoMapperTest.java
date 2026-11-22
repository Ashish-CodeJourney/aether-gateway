package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.Usage;
import org.junit.jupiter.api.Test;

import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionDtoMapperTest {

    private final ChatCompletionDtoMapper mapper = new ChatCompletionDtoMapper();

    @Test
    void mapsAnInboundRequestDtoToTheDomainRequest() {
        var dto = new ChatCompletionRequestDto(
                "mock",
                List.of(new ChatMessageDto("user", "hello")),
                false,
                null,
                null,
                null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.model()).isEqualTo("mock");
        assertThat(domain.messages()).containsExactly(new ChatMessage("user", "hello"));
        assertThat(domain.stream()).isFalse();
    }

    @Test
    void defaultsStreamToFalseWhenAbsentFromTheRequest() {
        var dto = new ChatCompletionRequestDto("mock", List.of(new ChatMessageDto("user", "hi")), null, null, null, null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.stream()).isFalse();
    }

    @Test
    void mapsTemperatureThrough() {
        var dto = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hi")), false, 0.7, null, null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.temperature()).isEqualTo(0.7);
    }

    @Test
    void carriesEachToolDefinitionThroughAsOpaqueJson() {
        JsonNode tool = json("""
                {"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}
                """);
        var dto = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hi")), false, null, List.of(tool), null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.tools()).hasSize(1);
        assertThat(json(domain.tools().get(0))).isEqualTo(tool);
    }

    @Test
    void carriesToolChoiceThroughAsOpaqueJsonWhetherAStringOrAnObject() {
        var asString = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hi")), false, null, null, null, json("\"required\""));
        JsonNode forced = json("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}");
        var asObject = new ChatCompletionRequestDto(
                "mock", List.of(new ChatMessageDto("user", "hi")), false, null, null, null, forced);

        assertThat(mapper.toDomain(asString).toolChoice()).isEqualTo("\"required\"");
        assertThat(json(mapper.toDomain(asObject).toolChoice())).isEqualTo(forced);
    }

    @Test
    void leavesToolChoiceAbsentWhenTheClientSentNone() {
        var dto = new ChatCompletionRequestDto("mock", List.of(new ChatMessageDto("user", "hi")), false, null, null, null);

        assertThat(mapper.toDomain(dto).toolChoice()).isNull();
    }

    @Test
    void mapsAnAssistantTurnWithToolCallsAndNullContent() {
        var call = new ToolCallDto(null, "call_1", "function", new FunctionCallDto("get_weather", "{\"city\":\"Paris\"}"));
        var dto = new ChatCompletionRequestDto("mock", List.of(
                new ChatMessageDto("user", "weather?"),
                new ChatMessageDto("assistant", null, List.of(call), null)), false, null, null, null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.messages().get(1)).isEqualTo(new ChatMessage(
                "assistant", "", List.of(new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}")), null));
    }

    @Test
    void mapsAToolResultTurnWithItsToolCallId() {
        var dto = new ChatCompletionRequestDto("mock", List.of(
                new ChatMessageDto("tool", "18C and sunny", null, "call_1")), false, null, null, null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.messages().get(0)).isEqualTo(new ChatMessage("tool", "18C and sunny", List.of(), "call_1"));
    }

    @Test
    void defaultsToolsToEmptyWhenAbsent() {
        var dto = new ChatCompletionRequestDto("mock", List.of(new ChatMessageDto("user", "hi")), false, null, null, null);

        ChatCompletionRequest domain = mapper.toDomain(dto);

        assertThat(domain.tools()).isEmpty();
    }

    @Test
    void mapsADomainResponseToTheOutboundDto() {
        var domainResponse = new ChatCompletionResponse(
                "id-1", "chat.completion", 1234L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi there"), "stop")),
                Usage.of(5, 3));

        ChatCompletionResponseDto dto = mapper.toDto(domainResponse);

        assertThat(dto.id()).isEqualTo("id-1");
        assertThat(dto.model()).isEqualTo("mock");
        assertThat(dto.choices()).hasSize(1);
        assertThat(dto.choices().get(0).message().content()).isEqualTo("hi there");
        assertThat(dto.usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void mapsANonFinalStreamChunkWithNoFinishReason() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 0, "hel", false);

        StreamChunkResponseDto dto = mapper.toDto(chunk, "mock");

        assertThat(dto.id()).isEqualTo("resp-1");
        assertThat(dto.choices().get(0).delta().content()).isEqualTo("hel");
        assertThat(dto.choices().get(0).finishReason()).isNull();
    }

    @Test
    void mapsAFinalStreamChunkWithAStopFinishReason() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 2, "", true);

        StreamChunkResponseDto dto = mapper.toDto(chunk, "mock");

        assertThat(dto.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void mapsAToolCallingResponseToOpenAiShapeWithNullContent() {
        var call = new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}");
        var domainResponse = new ChatCompletionResponse(
                "id-1", "chat.completion", 1234L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "", List.of(call), null), "tool_calls")),
                Usage.of(5, 3));

        JsonNode body = json(mapper.toJson(mapper.toDto(domainResponse)));

        JsonNode choice = body.get("choices").get(0);
        assertThat(choice.get("finish_reason").asString()).isEqualTo("tool_calls");
        assertThat(choice.get("message").get("content").isNull()).isTrue();
        assertThat(choice.get("message").get("tool_calls")).isEqualTo(json("""
                [{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}]
                """));
    }

    @Test
    void omitsToolFieldsFromAPlainTextResponse() {
        var domainResponse = new ChatCompletionResponse(
                "id-1", "chat.completion", 1234L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi there"), "stop")),
                Usage.of(5, 3));

        JsonNode message = json(mapper.toJson(mapper.toDto(domainResponse))).get("choices").get(0).get("message");

        assertThat(message.get("content").asString()).isEqualTo("hi there");
        assertThat(message.has("tool_calls")).isFalse();
        assertThat(message.has("tool_call_id")).isFalse();
    }

    @Test
    void aToolCallingResponseSurvivesTheJsonRoundTripTheCacheWouldUse() {
        var call = new ToolCall("call_1", "function", "get_weather", "{\"city\":\"Paris\"}");
        var domainResponse = new ChatCompletionResponse(
                "id-1", "chat.completion", 1234L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "", List.of(call), null), "tool_calls")),
                Usage.of(5, 3));
        ChatCompletionResponseDto dto = mapper.toDto(domainResponse);

        assertThat(mapper.responseFromJson(mapper.toJson(dto))).isEqualTo(dto);
    }

    @Test
    void mapsStreamedToolCallFragmentsToOpenAiDeltaShape() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 0, "", false, List.of(
                new ToolCallDelta(0, "call_1", "get_weather", ""),
                new ToolCallDelta(0, null, null, "{\"city\":")), null);

        JsonNode delta = json(mapper.toJson(mapper.toDto(chunk, "mock"))).get("choices").get(0).get("delta");

        assertThat(delta.get("tool_calls")).isEqualTo(json("""
                [{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}},
                 {"index":0,"function":{"arguments":"{\\"city\\":"}}]
                """));
        assertThat(delta.has("content")).isFalse();
    }

    @Test
    void usesTheProvidersFinishReasonOnTheLastStreamChunk() {
        var chunk = new ProviderResponse.StreamChunk("resp-1", 3, "", true, List.of(), "tool_calls");

        StreamChunkResponseDto dto = mapper.toDto(chunk, "mock");

        assertThat(dto.choices().get(0).finishReason()).isEqualTo("tool_calls");
    }

    private static final ObjectMapper TEST_JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        return TEST_JSON.readTree(text);
    }
}
