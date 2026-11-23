package com.aether.gateway.core.port;

/** Driving port behind POST /admin/cost-model/reload: re-reads cost-model.yaml without a restart. */
public interface CostModelReloadUseCase {
    void reload();
}
