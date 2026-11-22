package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code model} is the fully qualified "models/{id}" form batchEmbedContents requires on every entry. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiEmbedContentRequest(String model, WireGeminiEmbedContent content, Integer outputDimensionality) {
}
