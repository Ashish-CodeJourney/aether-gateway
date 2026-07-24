package com.aether.gateway.proxy.web;

import java.util.List;
import java.util.Map;

/**
 * {@code tools} is a raw passthrough (no tool-calling execution is
 * implemented by this project); its only use is F4.5's cache bypass
 * rule and F4.1's cache key, both of which only need each tool's name,
 * extracted in {@link ChatCompletionDtoMapper}.
 */
public record ChatCompletionRequestDto(
        String model,
        List<ChatMessageDto> messages,
        Boolean stream,
        Double temperature,
        List<Map<String, Object>> tools) {
}
