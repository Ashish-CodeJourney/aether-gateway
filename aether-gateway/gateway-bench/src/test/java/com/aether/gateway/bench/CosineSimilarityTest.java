package com.aether.gateway.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CosineSimilarityTest {

    @Test
    void isOneForIdenticalVectors() {
        float[] a = {1f, 2f, 3f};

        double similarity = CosineSimilarity.of(a, a);

        assertThat(similarity).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void isZeroForOrthogonalVectors() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};

        double similarity = CosineSimilarity.of(a, b);

        assertThat(similarity).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void isMinusOneForOppositeVectors() {
        float[] a = {1f, 0f};
        float[] b = {-1f, 0f};

        double similarity = CosineSimilarity.of(a, b);

        assertThat(similarity).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void isScaleInvariant() {
        float[] a = {1f, 2f, 3f};
        float[] b = {2f, 4f, 6f};

        double similarity = CosineSimilarity.of(a, b);

        assertThat(similarity).isCloseTo(1.0, within(1e-9));
    }
}
