package com.aether.gateway.core.domain;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * F6.3/F6.4: pure cost-model math, no I/O. The same calculation serves
 * both {@code cost_usd} (actual tokens from a completed provider call)
 * and {@code cache_savings_usd} (estimated tokens a cache hit avoided) -
 * the two differ only in which token counts the caller passes in, per
 * F6.4's definition of savings as "what the request would have cost."
 */
public final class CostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private CostCalculator() {
    }

    public static BigDecimal costUsd(ModelPricing pricing, long inputTokens, long outputTokens) {
        BigDecimal inputCost = pricing.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(inputTokens), MathContext.DECIMAL64)
                .divide(ONE_MILLION, MathContext.DECIMAL64);
        BigDecimal outputCost = pricing.outputPricePerMillion()
                .multiply(BigDecimal.valueOf(outputTokens), MathContext.DECIMAL64)
                .divide(ONE_MILLION, MathContext.DECIMAL64);
        return inputCost.add(outputCost);
    }
}
