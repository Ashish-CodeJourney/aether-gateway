package com.aether.gateway.core.domain;

import java.util.List;

/**
 * A request to embed one or more texts. {@code inputs} is always a list:
 * OpenAI's single-string {@code input} form is normalised to a one-element
 * list at the HTTP boundary. Token-id inputs are not modelled - the gateway
 * routes across providers with different tokenizers, so pre-tokenised input
 * would only ever be meaningful to one of them. {@code dimensions} is null
 * when the caller wants the model's native size.
 */
public record EmbeddingRequest(String model, List<String> inputs, Integer dimensions) {

    /** Matches OpenAI's own per-request input limit, so anything an OpenAI SDK sends is accepted while still bounding memory per request (F9.4). */
    public static final int MAX_INPUTS = 2048;

    public EmbeddingRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("input must be a non-empty string or array of strings");
        }
        if (inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("input must not exceed " + MAX_INPUTS + " items (got " + inputs.size() + ")");
        }
        if (inputs.stream().anyMatch(text -> text == null || text.isEmpty())) {
            throw new IllegalArgumentException("input must not contain empty strings");
        }
        if (dimensions != null && dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be a positive integer");
        }
        inputs = List.copyOf(inputs);
    }

    /** Same four-characters-per-token heuristic as {@link TokenEstimator}, with no output allowance: an embedding has no completion tokens. */
    public int estimatedPromptTokens() {
        return inputs.stream().mapToInt(text -> Math.max(1, text.length() / 4)).sum();
    }

    public EmbeddingRequest withModel(String upstreamModel) {
        return new EmbeddingRequest(upstreamModel, inputs, dimensions);
    }
}
