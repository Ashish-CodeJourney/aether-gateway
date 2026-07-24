package com.aether.gateway.bench;

/** A seed prompt and its paraphrase/adversarial variant, grouped by `pair_id`. */
public record CorpusPair(String pairId, String seedPrompt, String variantPrompt) {
}
