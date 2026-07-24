package com.aether.gateway.core.domain;

import java.math.BigDecimal;

/** F6.3: price per 1,000,000 tokens for a (provider, model) pair, from the configurable cost model. */
public record ModelPricing(String provider, String model, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
}
