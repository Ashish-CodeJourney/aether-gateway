package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoftLimitCalculatorTest {

    @Test
    void isNotNearLimitWithPlentyOfBudgetRemaining() {
        assertThat(SoftLimitCalculator.isNearMonthlyLimit(900, 1000L)).isFalse();
    }

    @Test
    void isNearLimitAtExactlyTenPercentRemaining() {
        assertThat(SoftLimitCalculator.isNearMonthlyLimit(100, 1000L)).isTrue();
    }

    @Test
    void isNearLimitWithLessThanTenPercentRemaining() {
        assertThat(SoftLimitCalculator.isNearMonthlyLimit(5, 1000L)).isTrue();
    }

    @Test
    void isNearLimitWhenFullyExhausted() {
        assertThat(SoftLimitCalculator.isNearMonthlyLimit(0, 1000L)).isTrue();
    }

    @Test
    void aNullBudgetMeansUnlimitedAndNeverNearLimit() {
        assertThat(SoftLimitCalculator.isNearMonthlyLimit(0, null)).isFalse();
    }
}
