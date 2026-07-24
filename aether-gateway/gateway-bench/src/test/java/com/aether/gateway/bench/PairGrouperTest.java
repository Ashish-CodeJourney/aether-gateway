package com.aether.gateway.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PairGrouperTest {

    @Test
    void pairsASeedWithItsVariantByPairId() {
        List<BenchmarkCorpusEntry> entries = List.of(
                new BenchmarkCorpusEntry("nd-001", "near-duplicate", "pair-001", "seed", "What's the capital of France?", null),
                new BenchmarkCorpusEntry("nd-002", "near-duplicate", "pair-001", "variant", "Capital city of France?", "hit"));

        List<CorpusPair> pairs = PairGrouper.group(entries);

        assertThat(pairs).containsExactly(
                new CorpusPair("pair-001", "What's the capital of France?", "Capital city of France?"));
    }

    @Test
    void ignoresEntriesWithNoPairId() {
        List<BenchmarkCorpusEntry> entries = List.of(
                new BenchmarkCorpusEntry("un-001", "unrelated", null, null, "Explain the offside rule.", "miss"));

        List<CorpusPair> pairs = PairGrouper.group(entries);

        assertThat(pairs).isEmpty();
    }

    @Test
    void ignoresARoleTaggedEntryThatHasNoPairId() {
        List<BenchmarkCorpusEntry> entries = List.of(
                new BenchmarkCorpusEntry("x-001", "near-duplicate", null, "seed", "orphaned seed", null),
                new BenchmarkCorpusEntry("x-002", "near-duplicate", null, "variant", "orphaned variant", "hit"));

        List<CorpusPair> pairs = PairGrouper.group(entries);

        assertThat(pairs).isEmpty();
    }

    @Test
    void dropsASeedThatHasNoMatchingVariant() {
        List<BenchmarkCorpusEntry> entries = List.of(
                new BenchmarkCorpusEntry("nd-001", "near-duplicate", "pair-001", "seed", "What's the capital of France?", null));

        List<CorpusPair> pairs = PairGrouper.group(entries);

        assertThat(pairs).isEmpty();
    }

    @Test
    void groupsMultiplePairsIndependently() {
        List<BenchmarkCorpusEntry> entries = List.of(
                new BenchmarkCorpusEntry("nd-001", "near-duplicate", "pair-001", "seed", "seed one", null),
                new BenchmarkCorpusEntry("nd-002", "near-duplicate", "pair-001", "variant", "variant one", "hit"),
                new BenchmarkCorpusEntry("nd-003", "near-duplicate", "pair-002", "seed", "seed two", null),
                new BenchmarkCorpusEntry("nd-004", "near-duplicate", "pair-002", "variant", "variant two", "hit"));

        List<CorpusPair> pairs = PairGrouper.group(entries);

        assertThat(pairs).containsExactly(
                new CorpusPair("pair-001", "seed one", "variant one"),
                new CorpusPair("pair-002", "seed two", "variant two"));
    }
}
