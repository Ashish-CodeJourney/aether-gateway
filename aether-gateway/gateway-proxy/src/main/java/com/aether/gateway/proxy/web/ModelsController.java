package com.aether.gateway.proxy.web;

import com.aether.gateway.core.port.ModelCatalogUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@RestController
public class ModelsController {

    private final ModelCatalogUseCase modelCatalogUseCase;
    private final Scheduler virtualThreadScheduler;

    public ModelsController(ModelCatalogUseCase modelCatalogUseCase, Scheduler virtualThreadScheduler) {
        this.modelCatalogUseCase = modelCatalogUseCase;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @GetMapping("/v1/models")
    public Mono<ModelListDto> listModels() {
        return Mono.fromCallable(modelCatalogUseCase::listModels)
                .subscribeOn(virtualThreadScheduler)
                .map(models -> new ModelListDto(
                        "list",
                        models.stream().map(m -> new ModelDto(m.id(), "model", m.status())).toList()));
    }
}
