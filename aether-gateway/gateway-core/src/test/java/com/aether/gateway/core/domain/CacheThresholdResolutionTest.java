package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Precedence: a valid X-Aether-Cache-Threshold header, then the route's
 * own cache.threshold, then the global default. The header may only make
 * matching stricter: anonymous callers share one cache namespace, so a
 * looser per-request threshold would let one caller poison answers that
 * other callers are then served.
 */
class CacheThresholdResolutionTest {

    private static final double GLOBAL_DEFAULT = 0.94;

    private static RouteCacheConfig routeWithThreshold(double threshold) {
        return new RouteCacheConfig(true, OptionalDouble.of(threshold), Duration.ofHours(1), true);
    }

    private static RouteCacheConfig routeWithoutThreshold() {
        return new RouteCacheConfig(true, OptionalDouble.empty(), Duration.ofHours(1), true);
    }

    @Test
    void usesTheRoutesThresholdWhenNoHeaderIsSent() {
        double threshold = CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, null);

        assertThat(threshold).isEqualTo(0.8);
    }

    @Test
    void usesTheGlobalDefaultWhenTheRouteSetsNoThreshold() {
        double threshold = CacheThresholdGate.effectiveThreshold(routeWithoutThreshold(), GLOBAL_DEFAULT, null);

        assertThat(threshold).isEqualTo(GLOBAL_DEFAULT);
    }

    @Test
    void aHeaderStricterThanTheRoutesThresholdWins() {
        double threshold = CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, "0.97");

        assertThat(threshold).isEqualTo(0.97);
    }

    @Test
    void aHeaderEqualToTheRoutesThresholdIsAccepted() {
        double threshold = CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, "0.8");

        assertThat(threshold).isEqualTo(0.8);
    }

    @Test
    void aHeaderOfExactlyOneIsAccepted() {
        double threshold = CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, "1.0");

        assertThat(threshold).isEqualTo(1.0);
    }

    @Test
    void aHeaderLooserThanTheRoutesThresholdIsRejected() {
        assertThatThrownBy(() -> CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, "0.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-Aether-Cache-Threshold")
                .hasMessageContaining("0.8");
    }

    @Test
    void aHeaderLooserThanTheGlobalDefaultIsRejectedWhenTheRouteSetsNoThreshold() {
        assertThatThrownBy(() -> CacheThresholdGate.effectiveThreshold(routeWithoutThreshold(), GLOBAL_DEFAULT, "0.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.94");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.01", "abc", "", "NaN", "Infinity", "-0.5"})
    void aHeaderThatIsNotANumberWithinRangeIsRejected(String header) {
        assertThatThrownBy(() -> CacheThresholdGate.effectiveThreshold(routeWithThreshold(0.8), GLOBAL_DEFAULT, header))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-Aether-Cache-Threshold");
    }
}
