package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F4.2: semantic hit only at or above the effective similarity threshold (see CacheThresholdResolutionTest for how it is chosen). */
class CacheThresholdGateTest {

    @Test
    void isAHitWhenSimilarityMeetsTheThreshold() {
        boolean hit = CacheThresholdGate.isHit(0.94, 0.94);

        assertThat(hit).isTrue();
    }

    @Test
    void isNotAHitWhenSimilarityIsBelowTheThreshold() {
        boolean hit = CacheThresholdGate.isHit(0.93, 0.94);

        assertThat(hit).isFalse();
    }
}
