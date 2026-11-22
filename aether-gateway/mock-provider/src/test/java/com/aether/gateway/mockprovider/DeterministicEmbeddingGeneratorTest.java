package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DeterministicEmbeddingGeneratorTest {

    private final DeterministicEmbeddingGenerator generator = new DeterministicEmbeddingGenerator();

    @Test
    void producesTheDefaultDimensionWhenNoneIsRequested() {
        assertThat(generator.embed("hello", null)).hasSize(DeterministicEmbeddingGenerator.DEFAULT_DIMENSIONS);
    }

    @Test
    void honoursARequestedDimension() {
        assertThat(generator.embed("hello", 3)).hasSize(3);
    }

    @Test
    void returnsTheSameVectorForTheSameTextEveryTime() {
        assertThat(new DeterministicEmbeddingGenerator().embed("hello", 16))
                .isEqualTo(new DeterministicEmbeddingGenerator().embed("hello", 16));
    }

    @Test
    void returnsDifferentVectorsForDifferentText() {
        assertThat(generator.embed("hello", 16)).isNotEqualTo(generator.embed("world", 16));
    }

    @Test
    void returnsUnitLengthVectorsSoCosineSimilarityIsADotProduct() {
        List<Float> vector = generator.embed("hello", 16);

        double squaredLength = vector.stream().mapToDouble(v -> v * v).sum();

        assertThat(squaredLength).isCloseTo(1.0, within(1e-5));
    }
}
