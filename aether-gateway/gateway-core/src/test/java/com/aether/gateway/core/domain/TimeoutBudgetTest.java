package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutBudgetTest {

    @Test
    void startsWithTheFullConfiguredBudget() {
        var budget = new TimeoutBudget(Duration.ofMillis(1000));

        assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void spendingReducesTheRemainingBudget() {
        var budget = new TimeoutBudget(Duration.ofMillis(1000));

        budget.spend(Duration.ofMillis(300));

        assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(700));
    }

    @Test
    void spendingMoreThanRemainingClampsToZeroNotNegative() {
        var budget = new TimeoutBudget(Duration.ofMillis(100));

        budget.spend(Duration.ofMillis(500));

        assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void isExhaustedOnceRemainingReachesZero() {
        var budget = new TimeoutBudget(Duration.ofMillis(100));

        assertThat(budget.isExhausted()).isFalse();

        budget.spend(Duration.ofMillis(100));

        assertThat(budget.isExhausted()).isTrue();
    }

    @Test
    void multipleSpendsAccumulateAcrossFailoverAttempts() {
        var budget = new TimeoutBudget(Duration.ofMillis(1000));

        budget.spend(Duration.ofMillis(200));
        budget.spend(Duration.ofMillis(300));
        budget.spend(Duration.ofMillis(100));

        assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(400));
    }
}
