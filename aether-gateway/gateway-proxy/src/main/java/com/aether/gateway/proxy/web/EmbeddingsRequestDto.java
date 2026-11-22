package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI's embeddings request. {@code input} stays untyped here because
 * the wire allows a string, an array of strings, or token-id arrays; the
 * mapper decides which are acceptable. Multi-word names are explicit so
 * the contract does not depend on the global naming strategy.
 */
public record EmbeddingsRequestDto(
        String model,
        Object input,
        @JsonProperty("encoding_format") String encodingFormat,
        Integer dimensions,
        String user) {
}
