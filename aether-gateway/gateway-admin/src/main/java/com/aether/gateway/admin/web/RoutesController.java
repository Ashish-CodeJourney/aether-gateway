package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.RoutingReloadUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** F2.7: minimal control-plane endpoint proving hot-reload works; full admin API (F8.2) arrives in Phase 10. */
@RestController
public class RoutesController {

    private final RoutingReloadUseCase routingReloadUseCase;
    private final Scheduler virtualThreadScheduler;

    public RoutesController(RoutingReloadUseCase routingReloadUseCase, Scheduler virtualThreadScheduler) {
        this.routingReloadUseCase = routingReloadUseCase;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping("/admin/routes/reload")
    public Mono<ResponseEntity<Object>> reload() {
        return Mono.fromRunnable(routingReloadUseCase::reload)
                .subscribeOn(virtualThreadScheduler)
                .then(Mono.just(ResponseEntity.ok().<Object>build()));
    }
}
