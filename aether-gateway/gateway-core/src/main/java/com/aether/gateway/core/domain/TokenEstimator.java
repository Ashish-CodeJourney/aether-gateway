package com.aether.gateway.core.domain;

/**
 * F5.6: a pessimistic total-token estimate (known prompt length plus a
 * configured worst-case output assumption), reserved against the
 * monthly budget before dispatch, per ADR-006. Output tokens are
 * genuinely unknown before the call completes; this estimate is
 * deliberately an overestimate, reconciled down afterward.
 */
public class TokenEstimator {

    private final long assumedMaxOutputTokens;

    public TokenEstimator(long assumedMaxOutputTokens) {
        this.assumedMaxOutputTokens = assumedMaxOutputTokens;
    }

    public long estimatePessimisticTotal(ChatCompletionRequest request) {
        long promptTokens = request.messages().stream()
                .mapToLong(m -> Math.max(1, m.content().length() / 4))
                .sum();
        return promptTokens + assumedMaxOutputTokens;
    }
}
