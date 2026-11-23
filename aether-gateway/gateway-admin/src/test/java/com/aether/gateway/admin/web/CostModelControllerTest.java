package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.CostModelReloadUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.scheduler.Schedulers;

import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CostModelControllerTest {

    private static WebTestClient clientFor(CostModelReloadUseCase useCase) {
        return WebTestClient.bindToController(new CostModelController(useCase, Schedulers.immediate())).build();
    }

    @Test
    void reloadsTheCostModelWithoutARestart() {
        var reloads = new AtomicInteger();

        clientFor(reloads::incrementAndGet).post()
                .uri("/admin/cost-model/reload")
                .exchange()
                .expectStatus().isOk();

        assertThat(reloads.get()).isEqualTo(1);
    }

    @Test
    void reportsAnUnreadableOrInvalidCostModelAsABadRequestRatherThanAServerError() {
        CostModelReloadUseCase failing = () -> {
            throw new UncheckedIOException("Failed to load cost model from /config/cost-model.yaml", new java.io.IOException("missing"));
        };

        clientFor(failing).post()
                .uri("/admin/cost-model/reload")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("cost-model.yaml"));
    }
}
