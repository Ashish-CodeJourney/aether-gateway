package com.aether.gateway.bench;

/** One labelled row of `benchmark/corpus/*.jsonl` (docs/design/requirements.md). */
public record BenchmarkCorpusEntry(
        String id,
        String bucket,
        String pairId,
        String role,
        String prompt,
        String expectedOutcome) {
}
