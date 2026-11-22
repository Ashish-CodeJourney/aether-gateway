package com.aether.gateway.mockprovider.web;

/** {@code input} is a string or an array of strings, as in OpenAI's API; {@code encoding_format} is accepted and ignored (always floats). */
public record EmbeddingRequestDto(String model, Object input, Integer dimensions) {
}
