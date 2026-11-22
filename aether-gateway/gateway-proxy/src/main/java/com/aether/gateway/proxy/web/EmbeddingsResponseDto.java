package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EmbeddingsResponseDto(String object, List<Item> data, String model, Usage usage) {

    /** {@code embedding} is a float array, or a base64 string when {@code encoding_format=base64}. */
    public record Item(String object, int index, Object embedding) {
    }

    public record Usage(@JsonProperty("prompt_tokens") int promptTokens, @JsonProperty("total_tokens") int totalTokens) {
    }
}
