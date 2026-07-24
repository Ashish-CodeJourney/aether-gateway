package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator(500);

    @Test
    void estimatesPromptTokensFromMessageContentLength() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "a".repeat(40))), false);

        long estimate = estimator.estimatePessimisticTotal(request);

        // 40 chars / 4 ~= 10 prompt tokens, plus the configured 500-token
        // pessimistic output assumption.
        assertThat(estimate).isEqualTo(510);
    }

    @Test
    void sumsAcrossMultipleMessages() {
        var request = new ChatCompletionRequest("mock", List.of(
                new ChatMessage("system", "a".repeat(20)),
                new ChatMessage("user", "b".repeat(20))), false);

        long estimate = estimator.estimatePessimisticTotal(request);

        assertThat(estimate).isEqualTo(10 + 500);
    }

    @Test
    void everyMessageContributesAtLeastOneToken() {
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);

        long estimate = estimator.estimatePessimisticTotal(request);

        assertThat(estimate).isEqualTo(1 + 500);
    }
}
