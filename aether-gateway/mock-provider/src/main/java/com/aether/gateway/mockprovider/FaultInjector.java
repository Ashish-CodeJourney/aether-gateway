package com.aether.gateway.mockprovider;

import java.util.Optional;
import java.util.Random;

/**
 * PRD section 14: decides whether a given request should fail, given the
 * X-Mock-Fail and X-Mock-Fail-Rate controls. X-Mock-Fail-Rate modulates
 * how often the configured X-Mock-Fail mode triggers; with no rate given,
 * a configured fail mode always triggers.
 */
public class FaultInjector {

    private final Random random;

    public FaultInjector(Random random) {
        this.random = random;
    }

    public Optional<MockFailure> determineFailure(MockControls controls) {
        if (controls.failMode() == null) {
            return Optional.empty();
        }
        double rate = controls.failRate() != null ? controls.failRate() : 1.0;
        if (random.nextDouble() < rate) {
            return Optional.of(MockFailure.forMode(controls.failMode()));
        }
        return Optional.empty();
    }
}
