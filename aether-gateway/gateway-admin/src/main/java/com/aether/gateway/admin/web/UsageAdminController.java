package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.UsageQueryPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Instant;

/** F8.4, PRD 13.2: {@code GET /admin/usage?from=&to=&groupBy=key|provider|model|route}. */
@RestController
public class UsageAdminController {

    private final UsageQueryPort usageQueryPort;
    private final Scheduler virtualThreadScheduler;

    public UsageAdminController(UsageQueryPort usageQueryPort, Scheduler virtualThreadScheduler) {
        this.usageQueryPort = usageQueryPort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @GetMapping("/admin/usage")
    public Mono<ResponseEntity<Object>> usage(
            @RequestParam("from") String from, @RequestParam("to") String to, @RequestParam("groupBy") String groupBy) {
        return Mono.fromCallable(() -> usageQueryPort.query(Instant.parse(from), Instant.parse(to), groupBy))
                .subscribeOn(virtualThreadScheduler)
                .map(rows -> ResponseEntity.ok((Object) rows.stream()
                        .map(r -> new UsageAggregateDto(r.groupValue(), r.requestCount(), r.inputTokens(), r.outputTokens(), r.costUsd(), r.savedUsd()))
                        .toList()))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body((Object) e.getMessage())));
    }
}
