package com.aether.gateway.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Null {@code dimensions} must be omitted, not sent as null: OpenAI rejects an explicit null. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireEmbeddingRequest(String model, List<String> input, String encodingFormat, Integer dimensions) {
}
