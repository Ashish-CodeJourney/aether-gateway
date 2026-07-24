package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F4.2: semantic hit only above a configured similarity threshold; F13.1: X-Aether-Cache-Threshold per-request override. */
class CacheThresholdGateTest {

    @Test
    void isAHitWhenSimilarityMeetsTheConfiguredThreshold() {
        boolean hit = CacheThresholdGate.isHit(0.94, 0.94, null);

        assertThat(hit).isTrue();
    }

    @Test
    void isNotAHitWhenSimilarityIsBelowTheConfiguredThreshold() {
        boolean hit = CacheThresholdGate.isHit(0.93, 0.94, null);

        assertThat(hit).isFalse();
    }

    @Test
    void aPerRequestOverrideReplacesTheConfiguredThreshold() {
        boolean hit = CacheThresholdGate.isHit(0.90, 0.94, 0.85);

        assertThat(hit).isTrue();
    }

    @Test
    void aPerRequestOverrideCanAlsoMakeTheGateStricter() {
        boolean hit = CacheThresholdGate.isHit(0.95, 0.90, 0.99);

        assertThat(hit).isFalse();
    }
}
