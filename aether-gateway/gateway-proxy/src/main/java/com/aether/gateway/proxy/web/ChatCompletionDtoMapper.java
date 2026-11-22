package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ToolCall;
import com.aether.gateway.core.domain.ToolCallDelta;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * The only place HTTP-facing DTOs and gateway-core domain types cross,
 * keeping the controller thin per the hexagonal boundary check
 * (see docs/design/module-boundaries.md).
 */
@Component
public class ChatCompletionDtoMapper {

    // Only needed for SSE data payloads, which the controller must hand
    // Spring a pre-serialised String for; every other response uses
    // Spring's own globally snake_case-configured ObjectMapper directly.
    private final ObjectMapper sseJsonMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public ChatCompletionRequest toDomain(ChatCompletionRequestDto dto) {
        List<ChatMessage> messages = dto.messages().stream()
                .map(this::toDomainMessage)
                .toList();
        boolean stream = dto.stream() != null && dto.stream();
        List<String> tools = dto.tools() == null ? List.of() : dto.tools().stream()
                .map(JsonNode::toString)
                .toList();
        String toolChoice = dto.toolChoice() == null || dto.toolChoice().isNull() ? null : dto.toolChoice().toString();
        return new ChatCompletionRequest(dto.model(), messages, stream, dto.temperature(), tools, toolChoice);
    }

    private ChatMessage toDomainMessage(ChatMessageDto dto) {
        List<ToolCall> toolCalls = dto.toolCalls() == null ? List.of() : dto.toolCalls().stream()
                .map(call -> new ToolCall(
                        call.id(),
                        call.type(),
                        call.function() == null ? null : call.function().name(),
                        call.function() == null ? null : call.function().arguments()))
                .toList();
        // OpenAI sends "content": null on an assistant turn that only calls tools.
        String content = dto.content() == null && !toolCalls.isEmpty() ? "" : dto.content();
        return new ChatMessage(dto.role(), content, toolCalls, dto.toolCallId());
    }

    public ChatCompletionResponseDto toDto(ChatCompletionResponse response) {
        List<ChoiceDto> choices = response.choices().stream()
                .map(this::toChoiceDto)
                .toList();
        var usage = new UsageDto(
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.usage().totalTokens());
        return new ChatCompletionResponseDto(
                response.id(), response.object(), response.created(), response.model(), choices, usage);
    }

    private ChoiceDto toChoiceDto(ChatCompletionChoice choice) {
        ChatMessage message = choice.message();
        List<ToolCallDto> toolCalls = message.toolCalls().stream()
                .map(call -> new ToolCallDto(null, call.id(), call.type(), new FunctionCallDto(call.functionName(), call.argumentsJson())))
                .toList();
        String content = message.content().isEmpty() && !toolCalls.isEmpty() ? null : message.content();
        var messageDto = new ChatMessageDto(message.role(), content, toolCalls, message.toolCallId());
        return new ChoiceDto(choice.index(), messageDto, choice.finishReason());
    }

    public StreamChunkResponseDto toDto(ProviderResponse.StreamChunk chunk, String model) {
        List<ToolCallDto> toolCalls = chunk.toolCallDeltas().stream()
                .map(this::toToolCallFragment)
                .toList();
        String content = chunk.deltaContent().isEmpty() && !toolCalls.isEmpty() ? null : chunk.deltaContent();
        var delta = new DeltaDto(content, toolCalls);
        String finishReason = chunk.last() ? (chunk.finishReason() != null ? chunk.finishReason() : "stop") : null;
        var choice = new StreamChoiceDto(0, delta, finishReason);
        return new StreamChunkResponseDto(
                chunk.id(), "chat.completion.chunk", Instant.now().getEpochSecond(), model, List.of(choice));
    }

    private ToolCallDto toToolCallFragment(ToolCallDelta delta) {
        boolean introducesTheCall = delta.id() != null || delta.functionName() != null;
        return new ToolCallDto(
                delta.index(),
                delta.id(),
                introducesTheCall ? "function" : null,
                new FunctionCallDto(delta.functionName(), delta.argumentsFragment()));
    }

    public String toJson(Object value) {
        return sseJsonMapper.writeValueAsString(value);
    }

    /** F4.1/F4.2: reconstructs a stored cache hit's body back into the response DTO, for a hit to look identical to a fresh response. */
    public ChatCompletionResponseDto responseFromJson(String json) {
        return sseJsonMapper.readValue(json, ChatCompletionResponseDto.class);
    }
}
