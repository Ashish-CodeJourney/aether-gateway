package com.aether.gateway.bench;

/** One labelled row of `benchmark/corpus/*.jsonl` (docs/plan/09-milestone-m6-benchmarking.md). */
public record BenchmarkCorpusEntry(
        String id,
        String bucket,
        String pairId,
        String role,
        String prompt,
        String expectedOutcome) {
}
