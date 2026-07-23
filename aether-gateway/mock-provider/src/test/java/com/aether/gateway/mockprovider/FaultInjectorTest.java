package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectorTest {

    @Test
    void noFaultConfiguredMeansNoFailure() {
        var injector = new FaultInjector(new Random(1));
        var controls = new MockControls(null, null, null, null, null, null);

        assertThat(injector.determineFailure(controls)).isEmpty();
    }

    @Test
    void failModeWithNoRateAlwaysFails() {
        var injector = new FaultInjector(new Random(1));
        var controls = new MockControls(null, "503", null, null, null, null);

        for (int i = 0; i < 20; i++) {
            assertThat(injector.determineFailure(controls)).isPresent();
        }
    }

    @Test
    void failModeMapsToTheCorrectHttpStatus() {
        var injector = new FaultInjector(new Random(1));

        assertThat(injector.determineFailure(new MockControls(null, "429", null, null, null, null)))
                .isPresent().get().extracting(MockFailure::httpStatus).isEqualTo(429);
        assertThat(injector.determineFailure(new MockControls(null, "500", null, null, null, null)))
                .isPresent().get().extracting(MockFailure::httpStatus).isEqualTo(500);
        assertThat(injector.determineFailure(new MockControls(null, "503", null, null, null, null)))
                .isPresent().get().extracting(MockFailure::httpStatus).isEqualTo(503);
    }

    @Test
    void malformedModeIsFlaggedDistinctlyFromHttpErrorModes() {
        var injector = new FaultInjector(new Random(1));

        var failure = injector.determineFailure(new MockControls(null, "malformed", null, null, null, null));

        assertThat(failure).isPresent();
        assertThat(failure.get().malformedBody()).isTrue();
        assertThat(failure.get().httpStatus()).isEqualTo(200);
    }

    @Test
    void timeoutModeCarriesAnArtificialDelay() {
        var injector = new FaultInjector(new Random(1));

        var failure = injector.determineFailure(new MockControls(null, "timeout", null, null, null, null));

        assertThat(failure).isPresent();
        assertThat(failure.get().artificialDelayMs()).isGreaterThan(0);
    }

    @Test
    void rejectsAnUnknownFailMode() {
        var injector = new FaultInjector(new Random(1));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> injector.determineFailure(new MockControls(null, "not-a-real-mode", null, null, null, null)));
    }

    @Test
    void failRateOfZeroNeverFailsAcrossManyTrials() {
        var injector = new FaultInjector(new Random(42));
        var controls = new MockControls(null, "500", 0.0, null, null, null);

        for (int i = 0; i < 200; i++) {
            assertThat(injector.determineFailure(controls)).isEmpty();
        }
    }

    @Test
    void failRateOfOneAlwaysFailsAcrossManyTrials() {
        var injector = new FaultInjector(new Random(42));
        var controls = new MockControls(null, "500", 1.0, null, null, null);

        for (int i = 0; i < 200; i++) {
            assertThat(injector.determineFailure(controls)).isPresent();
        }
    }

    @Test
    void failRateOfPoint3FailsRoughlyThirtyPercentOfTheTimeOverManyTrials() {
        var injector = new FaultInjector(new Random(7));
        var controls = new MockControls(null, "500", 0.3, null, null, null);

        int trials = 20_000;
        long failures = 0;
        for (int i = 0; i < trials; i++) {
            if (injector.determineFailure(controls).isPresent()) {
                failures++;
            }
        }

        double observedRate = (double) failures / trials;
        assertThat(observedRate).isBetween(0.27, 0.33);
    }
}
