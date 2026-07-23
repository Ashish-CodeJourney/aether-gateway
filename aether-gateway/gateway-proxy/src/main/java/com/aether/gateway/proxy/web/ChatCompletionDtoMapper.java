package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * The only place HTTP-facing DTOs and gateway-core domain types cross,
 * keeping the controller thin per the hexagonal boundary check
 * (docs/plan/HEXAGONAL-ARCHITECTURE-GUIDE.md section 4, item 4).
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
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
        boolean stream = dto.stream() != null && dto.stream();
        return new ChatCompletionRequest(dto.model(), messages, stream);
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
        var messageDto = new ChatMessageDto(choice.message().role(), choice.message().content());
        return new ChoiceDto(choice.index(), messageDto, choice.finishReason());
    }

    public StreamChunkResponseDto toDto(ProviderResponse.StreamChunk chunk) {
        var delta = new DeltaDto(chunk.deltaContent());
        var choice = new StreamChoiceDto(0, delta, chunk.last() ? "stop" : null);
        return new StreamChunkResponseDto(
                chunk.id(), "chat.completion.chunk", Instant.now().getEpochSecond(), null, List.of(choice));
    }

    public String toJson(Object value) {
        return sseJsonMapper.writeValueAsString(value);
    }
}
