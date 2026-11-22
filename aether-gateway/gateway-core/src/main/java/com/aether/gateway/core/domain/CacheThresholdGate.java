package com.aether.gateway.core.domain;

/** F4.2 threshold gate, and the precedence that picks the threshold it applies (PRD section 13.1's X-Aether-Cache-Threshold included). */
public final class CacheThresholdGate {

    private static final double MAXIMUM_THRESHOLD = 1.0;

    private CacheThresholdGate() {
    }

    public static boolean isHit(double similarity, double threshold) {
        return similarity >= threshold;
    }

    /**
     * Precedence: the request's {@code X-Aether-Cache-Threshold} header,
     * then the route's own {@code cache.threshold}, then the global
     * default. The header may only make matching stricter - between the
     * configured threshold and 1.0 inclusive. Anonymous callers share a
     * single cache namespace, so letting one caller loosen matching would
     * let it pull (and, on a miss, seed) entries other callers are then
     * served.
     *
     * @throws IllegalArgumentException if the header is not a number in that range
     */
    public static double effectiveThreshold(RouteCacheConfig route, double globalDefault, String requestedThreshold) {
        double configured = route.threshold().orElse(globalDefault);
        if (requestedThreshold == null) {
            return configured;
        }
        double requested = parseOrReject(requestedThreshold, configured);
        if (!(requested >= configured && requested <= MAXIMUM_THRESHOLD)) {
            throw invalidThreshold(requestedThreshold, configured);
        }
        return requested;
    }

    private static double parseOrReject(String requestedThreshold, double configured) {
        try {
            return Double.parseDouble(requestedThreshold.strip());
        } catch (NumberFormatException e) {
            throw invalidThreshold(requestedThreshold, configured);
        }
    }

    private static IllegalArgumentException invalidThreshold(String requestedThreshold, double configured) {
        return new IllegalArgumentException(
                "X-Aether-Cache-Threshold must be a number between " + configured + " (this route's configured threshold)"
                        + " and " + MAXIMUM_THRESHOLD + " inclusive; it can only make cache matching stricter, got '"
                        + requestedThreshold + "'");
    }
}
