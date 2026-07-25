package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.RoutingReloadUseCase;
import com.aether.gateway.router.routing.RoutingSource;
import com.aether.gateway.router.routing.SsrfProtectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** F2.7/F8.2: routing policy read + hot-reload control plane. */
@RestController
public class RoutesController {

    private final RoutingReloadUseCase routingReloadUseCase;
    private final RoutingSource routingSource;
    private final Scheduler virtualThreadScheduler;

    public RoutesController(RoutingReloadUseCase routingReloadUseCase, RoutingSource routingSource, Scheduler virtualThreadScheduler) {
        this.routingReloadUseCase = routingReloadUseCase;
        this.routingSource = routingSource;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping("/admin/routes/reload")
    public Mono<ResponseEntity<Object>> reload() {
        return Mono.fromRunnable(routingReloadUseCase::reload)
                .subscribeOn(virtualThreadScheduler)
                .then(Mono.just(ResponseEntity.ok().<Object>build()))
                // F9.3: a routing.yaml pointing a real provider at a
                // private address is a rejected *configuration*, not a
                // server fault - 400, not the 500 an unhandled
                // exception would otherwise surface as.
                .onErrorResume(SsrfProtectionException.class, e -> Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_REQUEST).<Object>body(e.getMessage())));
    }

    @GetMapping("/admin/routes")
    public Mono<ResponseEntity<Object>> list() {
        return Mono.fromCallable(routingSource::allRoutes)
                .subscribeOn(virtualThreadScheduler)
                .map(routes -> ResponseEntity.ok((Object) routes.values().stream()
                        .map(r -> new RouteConfigDto(r.alias(), r.chain().stream()
                                .map(m -> new RouteConfigDto.ChainMemberDto(m.provider(), m.model(), m.weight()))
                                .toList()))
                        .toList()));
    }
}
