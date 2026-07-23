package com.aether.gateway.proxy.web;

import java.util.List;

public record StreamChunkResponseDto(
        String id,
        String object,
        long created,
        String model,
        List<StreamChoiceDto> choices) {
}
