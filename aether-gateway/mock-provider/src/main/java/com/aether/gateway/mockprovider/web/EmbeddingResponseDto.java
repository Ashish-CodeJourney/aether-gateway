package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EmbeddingResponseDto(String object, List<Item> data, String model, Usage usage) {

    public record Item(String object, int index, List<Float> embedding) {
    }

    public record Usage(@JsonProperty("prompt_tokens") int promptTokens, @JsonProperty("total_tokens") int totalTokens) {
    }
}
