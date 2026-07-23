package com.aether.gateway.proxy.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Minimal liveness signal for Docker HEALTHCHECK now; the same endpoint
 * becomes the K8s readiness/liveness probe target in Phase 11 (M8). Full
 * actuator health (dependency checks) arrives with Phase 08 (M5).
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Mono<String> healthz() {
        return Mono.just("ok");
    }
}
