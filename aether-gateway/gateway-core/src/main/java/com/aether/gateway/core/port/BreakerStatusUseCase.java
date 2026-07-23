package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ProviderBreakerStatus;

import java.util.List;
import java.util.Optional;

/** F3.8: driving port behind GET /admin/providers/{name}/breaker. */
public interface BreakerStatusUseCase {
    List<ProviderBreakerStatus> allStatuses();

    Optional<ProviderBreakerStatus> statusFor(String provider);
}
