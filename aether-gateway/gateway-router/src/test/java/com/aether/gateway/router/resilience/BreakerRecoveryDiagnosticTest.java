package com.aether.gateway.router.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents a Resilience4j behaviour discovered the hard way during
 * manual M2 verification: {@code CircuitBreaker.getState()} does NOT
 * evaluate the lazy OPEN -> HALF_OPEN transition; it can report a stale
 * OPEN reading forever, even long after {@code waitDurationInOpenState}
 * has elapsed. Only an actual permission-acquiring call
 * ({@code tryAcquirePermission()}, {@code acquirePermission()}, or
 * {@code executeSupplier()}) triggers the transition. Every read site in
 * this codebase (ResilientRouter's request path, RouterBreakerStatus's
 * admin-endpoint reporting) must go through a permission-acquiring call,
 * never a bare {@code getState()}, or it will observe/report a breaker
 * as permanently open after it has actually recovered.
 */
class BreakerRecoveryDiagnosticTest {

    @Test
    void getStateAloneNeverObservesRecoveryEvenAfterTheWaitDurationElapses() throws InterruptedException {
        var breaker = openedBreaker();

        Thread.sleep(200);

        assertThat(breaker.getState())
                .as("getState() alone must not trigger the lazy OPEN -> HALF_OPEN transition")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void tryAcquirePermissionTriggersTheTransitionToHalfOpenAfterTheWaitDurationElapses() throws InterruptedException {
        var breaker = openedBreaker();

        Thread.sleep(200);

        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    private CircuitBreaker openedBreaker() {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        var breaker = CircuitBreaker.of("diag", config);
        breaker.onError(1, TimeUnit.MILLISECONDS, new RuntimeException("fail1"));
        breaker.onError(1, TimeUnit.MILLISECONDS, new RuntimeException("fail2"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        return breaker;
    }
}
