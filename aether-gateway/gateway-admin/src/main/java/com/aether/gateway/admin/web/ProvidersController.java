package com.aether.gateway.admin.web;

import com.aether.gateway.core.domain.ProviderBreakerStatus;
import com.aether.gateway.core.port.BreakerStatusUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** F3.8/F8.2: breaker-state observability, the primary provider-health signal this project exposes. */
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

    /** F8.2: {@code GET /admin/providers/health} - every known (provider, model) pair's breaker state at once. */
    @GetMapping("/admin/providers/health")
    public Mono<ResponseEntity<Object>> health() {
        return Mono.fromCallable(breakerStatusUseCase::allStatuses)
                .subscribeOn(virtualThreadScheduler)
                .map(statuses -> ResponseEntity.ok((Object) statuses.stream()
                        .map(s -> new BreakerStatusDto(s.provider(), s.model(), s.state().name()))
                        .toList()));
    }

    private ResponseEntity<Object> toResponse(ProviderBreakerStatus status) {
        return ResponseEntity.ok((Object) new BreakerStatusDto(status.provider(), status.model(), status.state().name()));
    }
}
