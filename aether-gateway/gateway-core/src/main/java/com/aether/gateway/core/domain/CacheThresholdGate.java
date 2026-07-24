package com.aether.gateway.core.domain;

/** F4.2 threshold gate; PRD section 13.1's X-Aether-Cache-Threshold supplies the optional per-request override. */
public final class CacheThresholdGate {

    private CacheThresholdGate() {
    }

    public static boolean isHit(double similarity, double configuredThreshold, Double perRequestOverride) {
        double threshold = perRequestOverride != null ? perRequestOverride : configuredThreshold;
        return similarity >= threshold;
    }
}
