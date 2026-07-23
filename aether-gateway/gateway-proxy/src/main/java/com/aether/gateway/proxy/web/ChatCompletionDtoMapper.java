package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The only place HTTP-facing DTOs and gateway-core domain types cross,
 * keeping the controller thin per the hexagonal boundary check
 * (docs/plan/HEXAGONAL-ARCHITECTURE-GUIDE.md section 4, item 4).
 */
@Component
public class ChatCompletionDtoMapper {

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
}
