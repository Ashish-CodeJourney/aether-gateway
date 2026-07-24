package com.aether.gateway.proxy.config;

import com.aether.gateway.core.port.BreakerStatusUseCase;
import com.aether.gateway.core.port.MetricsPort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F6.1 / F3.8: periodically pushes current breaker state into
 * {@link MetricsPort} for the breaker-state-timeline dashboard panel.
 * Polling rather than push-per-transition, since Resilience4j's
 * {@code CircuitBreakerRegistry} has no transition-listener hook wired
 * up elsewhere in this codebase; a 5s poll is frequent enough for a
 * dashboard timeline without adding load.
 */
@Component
public class BreakerMetricsPoller {

    private final BreakerStatusUseCase breakerStatusUseCase;
    private final MetricsPort metricsPort;

    public BreakerMetricsPoller(BreakerStatusUseCase breakerStatusUseCase, MetricsPort metricsPort) {
        this.breakerStatusUseCase = breakerStatusUseCase;
        this.metricsPort = metricsPort;
    }

    @Scheduled(fixedRate = 5000)
    public void pollBreakerStates() {
        for (var status : breakerStatusUseCase.allStatuses()) {
            metricsPort.recordBreakerState(status.provider(), status.model(), status.state().name());
        }
    }
}
