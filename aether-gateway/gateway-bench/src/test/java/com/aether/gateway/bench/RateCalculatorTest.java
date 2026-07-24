package com.aether.gateway.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateCalculatorTest {

    @Test
    void fractionIsTheProportionOfItemsMatchingThePredicate() {
        List<Double> similarities = List.of(0.99, 0.5, 0.96, 0.10);

        double fraction = RateCalculator.fraction(similarities, s -> s >= 0.95);

        assertThat(fraction).isEqualTo(0.5);
    }

    @Test
    void fractionIsZeroWhenNothingMatches() {
        List<Double> similarities = List.of(0.10, 0.20);

        double fraction = RateCalculator.fraction(similarities, s -> s >= 0.95);

        assertThat(fraction).isEqualTo(0.0);
    }

    @Test
    void fractionIsOneWhenEverythingMatches() {
        List<Double> similarities = List.of(0.99, 0.97);

        double fraction = RateCalculator.fraction(similarities, s -> s >= 0.95);

        assertThat(fraction).isEqualTo(1.0);
    }

    @Test
    void fractionOfAnEmptyListIsUndefinedAndRejected() {
        List<Double> empty = List.of();

        assertThatThrownBy(() -> RateCalculator.fraction(empty, s -> s >= 0.95))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
