package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ModelPricing;

import java.util.Optional;

/** F6.3: driven port for the configurable per-(provider, model) cost model. */
public interface CostModelPort {
    Optional<ModelPricing> pricingFor(String provider, String model);
}
