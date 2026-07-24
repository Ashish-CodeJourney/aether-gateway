package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.RequestMetrics;

/** F6.1: driven port for metrics emission. gateway-observability implements this against Micrometer. */
public interface MetricsPort {

    void recordRequest(RequestMetrics metrics);

    /** F3.8/F6.1: current breaker state per (provider, model), for the breaker-state-timeline dashboard panel. */
    void recordBreakerState(String provider, String model, String state);

    void streamStarted();

    void streamEnded();
}
