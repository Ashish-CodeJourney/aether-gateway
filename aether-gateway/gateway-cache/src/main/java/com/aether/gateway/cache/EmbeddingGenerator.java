package com.aether.gateway.cache;

import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * F4.2 / F4.3: in-process embedding generation via ONNX
 * (all-MiniLM-L6-v2, 384-dim), no network hop per request. ADR-007
 * scopes Spring AI usage to exactly this: the embedding model itself,
 * nothing else.
 *
 * <p>{@link TransformersEmbeddingModel}'s default model/tokenizer URIs
 * point at all-MiniLM-L6-v2 already, matching the PRD's chosen model
 * exactly; a one-time download happens on first use (or at startup, via
 * {@link #warmUp()}), after which {@link TransformersEmbeddingModel}'s
 * own {@code resourceCacheDirectory} makes every subsequent call fully
 * offline. This satisfies F4.3's "no network hop, no API cost" per
 * embedding call; it does not claim the process never touches the
 * network at all during its lifetime.
 */
public class EmbeddingGenerator {

    private final TransformersEmbeddingModel model;

    public EmbeddingGenerator(String resourceCacheDirectory) {
        this.model = new TransformersEmbeddingModel();
        this.model.setResourceCacheDirectory(resourceCacheDirectory);
        try {
            this.model.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize the embedding model", e);
        }
    }

    /** Forces the one-time model download/load to happen now rather than on the first real request. */
    public void warmUp() {
        embed("warm up");
    }

    public float[] embed(String text) {
        return model.embed(text);
    }
}
