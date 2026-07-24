package com.aether.gateway.core.domain;

/** F5.4: soft limit warning threshold, distinct from hard-limit rejection. */
public final class SoftLimitCalculator {

    private static final double WARNING_THRESHOLD_FRACTION = 0.10;

    private SoftLimitCalculator() {
    }

    public static boolean isNearMonthlyLimit(long remainingTokens, Long monthlyBudget) {
        if (monthlyBudget == null) {
            return false;
        }
        return remainingTokens <= monthlyBudget * WARNING_THRESHOLD_FRACTION;
    }
}
