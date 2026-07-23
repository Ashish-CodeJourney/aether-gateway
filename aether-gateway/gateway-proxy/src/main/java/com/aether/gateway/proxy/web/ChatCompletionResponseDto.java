package com.aether.gateway.proxy.web;

import java.util.List;

public record ChatCompletionResponseDto(
        String id,
        String object,
        long created,
        String model,
        List<ChoiceDto> choices,
        UsageDto usage) {
}
