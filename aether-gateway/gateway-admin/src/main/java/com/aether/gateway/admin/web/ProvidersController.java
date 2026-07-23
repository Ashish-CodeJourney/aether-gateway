package com.aether.gateway.admin.web;

import com.aether.gateway.core.domain.ProviderBreakerStatus;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** F3.8: minimal breaker-state observability endpoint; full provider health API (F8.2) arrives in Phase 10. */
@RestController
public class ProvidersController {

    private final BreakerStatusUseCase breakerStatusUseCase;
    private final Scheduler virtualThreadScheduler;

    public ProvidersController(BreakerStatusUseCase breakerStatusUseCase, Scheduler virtualThreadScheduler) {
        this.breakerStatusUseCase = breakerStatusUseCase;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @GetMapping("/admin/providers/{name}/breaker")
    public Mono<ResponseEntity<Object>> breakerStatus(@PathVariable("name") String name) {
        return Mono.fromCallable(() -> breakerStatusUseCase.statusFor(name))
                .subscribeOn(virtualThreadScheduler)
                .map(maybeStatus -> maybeStatus
                        .map(this::toResponse)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    private ResponseEntity<Object> toResponse(ProviderBreakerStatus status) {
        return ResponseEntity.ok((Object) new BreakerStatusDto(status.provider(), status.model(), status.state().name()));
    }
}
