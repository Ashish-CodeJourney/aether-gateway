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

                The guard only ever *rejects* candidate hits (it adds no new
                similarity-based hits), so at every threshold `hit_rate_guarded <=
                hit_rate_no_guard` and `false_hit_rate_guarded <=
                false_hit_rate_no_guard` - verified directly by this test's own
                assertions on every swept threshold, not just the reference point
                above.
                """.formatted(
                nearDuplicatePairs.size(), adversarialPairs.size(),
                falseHitReduction * 100, falseHitRatesNoGuard.get(referenceIndex) * 100, falseHitRatesGuarded.get(referenceIndex) * 100,
                recallCost * 100, hitRatesNoGuard.get(referenceIndex) * 100, hitRatesGuarded.get(referenceIndex) * 100);

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

    private static List<Double> thresholds() {
        List<Double> values = new ArrayList<>();
        for (int hundredths = 80; hundredths <= 99; hundredths++) {
            values.add(hundredths / 100.0);
        }
        return values;
    }
}
