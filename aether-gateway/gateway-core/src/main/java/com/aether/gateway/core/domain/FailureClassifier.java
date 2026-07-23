package com.aether.gateway.core.domain;

import java.util.Set;

/** F3.5: distinguishes retryable failures from terminal ones. */
public final class FailureClassifier {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 503, 504);

    private FailureClassifier() {
    }

    public static boolean isRetryable(int httpStatus) {
        return RETRYABLE_STATUSES.contains(httpStatus);
    }
}
