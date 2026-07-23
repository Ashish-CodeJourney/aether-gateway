package com.aether.gateway.mockprovider.web;

import java.util.List;

public record ChatCompletionRequestDto(String model, List<ChatMessageDto> messages, Boolean stream) {
}
