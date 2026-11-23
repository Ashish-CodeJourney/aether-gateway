package com.aether.gateway.bench;

import com.aether.gateway.cache.EmbeddingGenerator;
import com.aether.gateway.core.domain.EntityNumericGuard;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiment 2 of the M6 benchmarking milestone (docs/design/requirements.md)
 * task 3: re-runs experiment 1's sweep with {@link EntityNumericGuard}
 * on and off, quantifying how much false-hit rate the guard removes and
 * how much legitimate recall (hit rate) it costs.
 */
class EntityGuardAblationExperimentTest {

    private static final List<Double> THRESHOLDS = thresholds();

    @Test
    void sweepsWithAndWithoutTheEntityGuardAndWritesTheCsv() throws IOException {
        Path corpusDir = Path.of("../../benchmark/corpus").toAbsolutePath().normalize();
        Path resultsDir = Path.of("../../benchmark/results").toAbsolutePath().normalize();
        Files.createDirectories(resultsDir);

        List<CorpusPair> nearDuplicatePairs = PairGrouper.group(CorpusLoader.load(corpusDir.resolve("near-duplicates.jsonl")));
        List<CorpusPair> adversarialPairs = PairGrouper.group(CorpusLoader.load(corpusDir.resolve("adversarial-near-misses.jsonl")));
        assertThat(nearDuplicatePairs).isNotEmpty();
        assertThat(adversarialPairs).isNotEmpty();

        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "aether-onnx-cache-test");
        Files.createDirectories(cacheDir);
        EmbeddingGenerator embeddingGenerator = new EmbeddingGenerator(cacheDir.toString());
        embeddingGenerator.warmUp();

        List<PairMeasurement> hitMeasurements = measure(nearDuplicatePairs, embeddingGenerator);
        List<PairMeasurement> falseHitMeasurements = measure(adversarialPairs, embeddingGenerator);

        StringBuilder csv = new StringBuilder(
                "threshold,hit_rate_no_guard,hit_rate_guarded,false_hit_rate_no_guard,false_hit_rate_guarded\n");
        List<Double> hitRatesNoGuard = new ArrayList<>();
        List<Double> hitRatesGuarded = new ArrayList<>();
        List<Double> falseHitRatesNoGuard = new ArrayList<>();
        List<Double> falseHitRatesGuarded = new ArrayList<>();

        for (double threshold : THRESHOLDS) {
            double hitRateNoGuard = RateCalculator.fraction(hitMeasurements, m -> m.similarity() >= threshold);
            double hitRateGuarded = RateCalculator.fraction(hitMeasurements, m -> m.similarity() >= threshold && m.guardAllows());
            double falseHitRateNoGuard = RateCalculator.fraction(falseHitMeasurements, m -> m.similarity() >= threshold);
            double falseHitRateGuarded = RateCalculator.fraction(falseHitMeasurements, m -> m.similarity() >= threshold && m.guardAllows());

            hitRatesNoGuard.add(hitRateNoGuard);
            hitRatesGuarded.add(hitRateGuarded);
            falseHitRatesNoGuard.add(falseHitRateNoGuard);
            falseHitRatesGuarded.add(falseHitRateGuarded);

            // The guard can only ever reject a candidate hit, never admit
            // a new one, so guarded rates can never exceed unguarded ones.
            assertThat(hitRateGuarded).isLessThanOrEqualTo(hitRateNoGuard);
            assertThat(falseHitRateGuarded).isLessThanOrEqualTo(falseHitRateNoGuard);

            csv.append(String.format(Locale.ROOT, "%.2f,%.4f,%.4f,%.4f,%.4f%n",
                    threshold, hitRateNoGuard, hitRateGuarded, falseHitRateNoGuard, falseHitRateGuarded));
        }

        Path csvPath = resultsDir.resolve("experiment-2-guard-ablation.csv");
        Files.writeString(csvPath, csv.toString(), StandardCharsets.UTF_8);

        Map<String, List<Double>> series = new LinkedHashMap<>();
        series.put("false-hit rate, no guard", falseHitRatesNoGuard);
        series.put("false-hit rate, guarded", falseHitRatesGuarded);
        series.put("hit rate, no guard", hitRatesNoGuard);
        series.put("hit rate, guarded", hitRatesGuarded);
        String svg = SvgLineChart.render("Experiment 2: entity guard ablation", THRESHOLDS, series);
        Files.writeString(resultsDir.resolve("experiment-2-guard-ablation.svg"), svg, StandardCharsets.UTF_8);

        // Reference threshold: the M4/experiment-1 illustrative default
        // (the M4 semantic-cache milestone), reported at the
        // exact index this sweep also covers (0.94 is index 14: 0.80 + 14*0.01).
        int referenceIndex = THRESHOLDS.indexOf(0.94);
        double falseHitReduction = falseHitRatesNoGuard.get(referenceIndex) - falseHitRatesGuarded.get(referenceIndex);
        double recallCost = hitRatesNoGuard.get(referenceIndex) - hitRatesGuarded.get(referenceIndex);
        String residualFalseHits = residualFalseHits(falseHitMeasurements, adversarialPairs, 0.94);
        GuardLatency latency = GuardLatency.measure(
                promptsOf(corpusDir, "near-duplicates.jsonl", "adversarial-near-misses.jsonl", "unrelated.jsonl"),
                promptsOf(corpusDir, "long-context.jsonl"));

        String report = """
                # Experiment 2: entity guard ablation

                Experiment 2 of the M6 benchmarking milestone (docs/design/requirements.md).
                Generated by `EntityGuardAblationExperimentTest`, re-running experiment
                1's sweep with `EntityNumericGuard` on and off, against the same full
                corpus (%d near-duplicate pairs, %d adversarial pairs).

                ![guard ablation chart](experiment-2-guard-ablation.svg)

                Raw data: `experiment-2-guard-ablation.csv`.

                ## Guard effect at threshold 0.94

                - False-hit rate reduction: %.1f%% (%.1f%% -> %.1f%%)
                - Recall cost: %.1f%% (%.1f%% -> %.1f%%)

                ## Adversarial pairs the guard still allows at threshold 0.94

                %s
                ## Guard cost

                `EntityNumericGuard.fingerprint` runs once per semantic lookup (the
                stored side is persisted at write time). Mean wall-clock time per
                call, single thread, after warm-up, on this run's machine:

                - Short prompts (%d near-duplicate, adversarial and unrelated prompts): %.1f us
                - Long-context prompts (%d prompts, ~4-5k tokens each): %.2f ms

                Embedding the same prompt with all-MiniLM-L6-v2 is the dominant
                per-lookup cost (see experiment 3).

                The guard only ever *rejects* candidate hits (it adds no new
                similarity-based hits), so at every threshold `hit_rate_guarded <=
                hit_rate_no_guard` and `false_hit_rate_guarded <=
                false_hit_rate_no_guard` - verified directly by this test's own
                assertions on every swept threshold, not just the reference point
                above.
                """.formatted(
                nearDuplicatePairs.size(), adversarialPairs.size(),
                falseHitReduction * 100, falseHitRatesNoGuard.get(referenceIndex) * 100, falseHitRatesGuarded.get(referenceIndex) * 100,
                recallCost * 100, hitRatesNoGuard.get(referenceIndex) * 100, hitRatesGuarded.get(referenceIndex) * 100,
                residualFalseHits,
                latency.shortPromptCount(), latency.shortPromptMicros(),
                latency.longPromptCount(), latency.longPromptMillis());

        Files.writeString(resultsDir.resolve("experiment-2-guard-ablation.md"), report, StandardCharsets.UTF_8);

        assertThat(csvPath).exists();
        assertThat(Files.readAllLines(csvPath)).hasSize(THRESHOLDS.size() + 1);
    }

    private static List<PairMeasurement> measure(List<CorpusPair> pairs, EmbeddingGenerator embeddingGenerator) {
        List<PairMeasurement> measurements = new ArrayList<>();
        for (CorpusPair pair : pairs) {
            float[] seedEmbedding = embeddingGenerator.embed(pair.seedPrompt());
            float[] variantEmbedding = embeddingGenerator.embed(pair.variantPrompt());
            double similarity = CosineSimilarity.of(seedEmbedding, variantEmbedding);
            boolean guardAllows = EntityNumericGuard.sameFingerprint(pair.seedPrompt(), pair.variantPrompt());
            measurements.add(new PairMeasurement(pair.pairId(), similarity, guardAllows));
        }
        return measurements;
    }

    private record PairMeasurement(String pairId, double similarity, boolean guardAllows) {
    }

    private static String residualFalseHits(List<PairMeasurement> measurements, List<CorpusPair> pairs, double threshold) {
        Map<String, CorpusPair> pairsById = new LinkedHashMap<>();
        pairs.forEach(pair -> pairsById.put(pair.pairId(), pair));
        List<String> lines = measurements.stream()
                .filter(m -> m.similarity() >= threshold && m.guardAllows())
                .map(m -> String.format(Locale.ROOT, "- `%s` (similarity %.3f): \"%s\" vs \"%s\"",
                        m.pairId(), m.similarity(), pairsById.get(m.pairId()).seedPrompt(), pairsById.get(m.pairId()).variantPrompt()))
                .toList();
        return lines.isEmpty() ? "None.\n" : String.join("\n", lines) + "\n";
    }

    private static List<String> promptsOf(Path corpusDir, String... files) throws IOException {
        List<String> prompts = new ArrayList<>();
        for (String file : files) {
            CorpusLoader.load(corpusDir.resolve(file)).forEach(entry -> prompts.add(entry.prompt()));
        }
        return prompts;
    }

    private record GuardLatency(int shortPromptCount, double shortPromptMicros, int longPromptCount, double longPromptMillis) {

        static GuardLatency measure(List<String> shortPrompts, List<String> longPrompts) {
            double shortNanos = meanNanosPerCall(shortPrompts, 200);
            double longNanos = meanNanosPerCall(longPrompts, 10);
            return new GuardLatency(shortPrompts.size(), shortNanos / 1_000, longPrompts.size(), longNanos / 1_000_000);
        }

        private static double meanNanosPerCall(List<String> prompts, int rounds) {
            long tokens = 0;
            for (int round = 0; round < rounds; round++) {
                tokens += prompts.stream().mapToInt(prompt -> EntityNumericGuard.fingerprint(prompt).size()).sum();
            }
            long start = System.nanoTime();
            for (int round = 0; round < rounds; round++) {
                tokens += prompts.stream().mapToInt(prompt -> EntityNumericGuard.fingerprint(prompt).size()).sum();
            }
            long elapsed = System.nanoTime() - start;
            assertThat(tokens).isPositive();
            return (double) elapsed / rounds / prompts.size();
        }
    }

    private static List<Double> thresholds() {
        List<Double> values = new ArrayList<>();
        for (int hundredths = 80; hundredths <= 99; hundredths++) {
            values.add(hundredths / 100.0);
        }
        return values;
    }
}
