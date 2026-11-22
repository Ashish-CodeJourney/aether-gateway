package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** {@code usage} appears only on the trailing usage chunk, whose {@code choices} is empty. */
public record StreamChunkResponseDto(
        String id,
        String object,
        long created,
        String model,
        List<StreamChoiceDto> choices,
        @JsonInclude(JsonInclude.Include.NON_NULL) UsageDto usage) {
}
