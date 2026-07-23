package com.aether.gateway.proxy.web;

import java.util.List;

public record ChatCompletionRequestDto(String model, List<ChatMessageDto> messages, Boolean stream) {
}
