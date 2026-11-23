package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.CostModelReloadUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.UncheckedIOException;

/** Hot-reloads cost-model.yaml, so a price change needs no redeploy. */
@RestController
public class CostModelController {

    private final CostModelReloadUseCase costModelReloadUseCase;
    private final Scheduler virtualThreadScheduler;

    public CostModelController(CostModelReloadUseCase costModelReloadUseCase, Scheduler virtualThreadScheduler) {
        this.costModelReloadUseCase = costModelReloadUseCase;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping("/admin/cost-model/reload")
    public Mono<ResponseEntity<Object>> reload() {
        return Mono.fromRunnable(costModelReloadUseCase::reload)
                .subscribeOn(virtualThreadScheduler)
                .then(Mono.just(ResponseEntity.ok().<Object>build()))
                // A missing or invalid file is a configuration problem the
                // operator can fix, not a server fault; the old prices stay
                // in force until a reload succeeds.
                .onErrorResume(e -> e instanceof IllegalArgumentException || e instanceof UncheckedIOException,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).<Object>body(e.getMessage())));
    }
}
