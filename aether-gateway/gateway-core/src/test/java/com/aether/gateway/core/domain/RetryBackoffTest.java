package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(100, 2000);

    @Test
    void firstAttemptDelayIsBetweenZeroAndBaseDelay() {
        for (int i = 0; i < 200; i++) {
            long delay = backoff.delayMillis(0, new Random(i));
            assertThat(delay).isBetween(0L, 100L);
        }
    }

    @Test
    void delayNeverExceedsTheConfiguredCapEvenAtHighAttemptCounts() {
        for (int i = 0; i < 200; i++) {
            long delay = backoff.delayMillis(10, new Random(i));
            assertThat(delay).isBetween(0L, 2000L);
        }
    }

    @Test
    void delayGrowsExponentiallyWithAttemptNumberOnAverage() {
        long attempt0Avg = averageDelay(0, 500);
        long attempt2Avg = averageDelay(2, 500);

        // full jitter means each individual sample is random in [0, cap],
        // but the average across many samples should still grow with the
        // attempt number until the cap is reached.
        assertThat(attempt2Avg).isGreaterThan(attempt0Avg);
    }

    @Test
    void isDeterministicForAGivenRandomSeed() {
        long first = backoff.delayMillis(1, new Random(7));
        long second = backoff.delayMillis(1, new Random(7));

        assertThat(first).isEqualTo(second);
    }

    private long averageDelay(int attempt, int trials) {
        long total = 0;
        for (int i = 0; i < trials; i++) {
            total += backoff.delayMillis(attempt, new Random(i));
        }
        return total / trials;
    }
}
