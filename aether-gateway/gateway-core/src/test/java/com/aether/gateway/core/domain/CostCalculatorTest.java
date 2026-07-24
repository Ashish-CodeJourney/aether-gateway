package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** F6.3: cost computed from a configurable per-(provider, model) price-per-1M-tokens table. */
class CostCalculatorTest {

    private final ModelPricing pricing = new ModelPricing("mock", "mock", new BigDecimal("0.50"), new BigDecimal("1.50"));

    @Test
    void computesCostFromInputAndOutputTokenPrices() {
        // 1,000,000 input tokens at $0.50/1M + 1,000,000 output tokens at $1.50/1M = $2.00
        BigDecimal cost = CostCalculator.costUsd(pricing, 1_000_000, 1_000_000);

        assertThat(cost.doubleValue()).isCloseTo(2.00, offset(0.0001));
    }

    @Test
    void computesCostForASmallRealisticRequest() {
        // 100 input tokens: 100/1_000_000 * 0.50 = 0.00005
        // 50 output tokens: 50/1_000_000 * 1.50 = 0.000075
        BigDecimal cost = CostCalculator.costUsd(pricing, 100, 50);

        assertThat(cost.doubleValue()).isCloseTo(0.000125, offset(0.0000001));
    }

    @Test
    void zeroTokensCostsNothing() {
        BigDecimal cost = CostCalculator.costUsd(pricing, 0, 0);

        assertThat(cost.doubleValue()).isEqualTo(0.0);
    }

    @Test
    void cacheSavingsEqualsWhatTheRequestWouldHaveCostAtTheSamePricing() {
        // F6.4: cache_savings_usd is exactly the cost the request would
        // have incurred had it gone to the provider instead of being
        // served from cache - the same calculation, applied to the
        // estimated (not actual, since no provider call happened) tokens.
        BigDecimal savings = CostCalculator.costUsd(pricing, 200, 100);

        assertThat(savings.doubleValue()).isCloseTo(0.00025, offset(0.0000001));
    }
}
