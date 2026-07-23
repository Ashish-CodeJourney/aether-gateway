package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void classifies429AsRetryable() {
        assertThat(FailureClassifier.isRetryable(429)).isTrue();
    }

    @Test
    void classifies503AsRetryable() {
        assertThat(FailureClassifier.isRetryable(503)).isTrue();
    }

    @Test
    void classifies504TimeoutAsRetryable() {
        assertThat(FailureClassifier.isRetryable(504)).isTrue();
    }

    @Test
    void classifies400AsTerminal() {
        assertThat(FailureClassifier.isRetryable(400)).isFalse();
    }

    @Test
    void classifies401AsTerminal() {
        assertThat(FailureClassifier.isRetryable(401)).isFalse();
    }

    @Test
    void classifies404AsTerminal() {
        assertThat(FailureClassifier.isRetryable(404)).isFalse();
    }

    @Test
    void classifies500AsTerminalNotRetryable() {
        // F3.5 explicitly lists 429/503/timeout as retryable and 400/401
        // as terminal; a generic 500 is a provider bug, not treated as
        // transient, so it is terminal (does not trigger retry storms
        // against a provider returning garbage).
        assertThat(FailureClassifier.isRetryable(500)).isFalse();
    }
}
