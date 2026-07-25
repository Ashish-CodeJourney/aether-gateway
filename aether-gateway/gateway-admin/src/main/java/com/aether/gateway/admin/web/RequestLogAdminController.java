package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.RequestLogQueryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** The console's request explorer screen: {@code GET /admin/requests?limit=}. */
@RestController
public class RequestLogAdminController {

    private final RequestLogQueryPort requestLogQueryPort;
    private final Scheduler virtualThreadScheduler;

    public RequestLogAdminController(RequestLogQueryPort requestLogQueryPort, Scheduler virtualThreadScheduler) {
        this.requestLogQueryPort = requestLogQueryPort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @GetMapping("/admin/requests")
    public Mono<ResponseEntity<Object>> recent(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> requestLogQueryPort.recent(limit))
                .subscribeOn(virtualThreadScheduler)
                .map(entries -> ResponseEntity.ok((Object) entries.stream()
                        .map(e -> new RequestLogEntryDto(
                                e.id().toString(), e.apiKeyId().toString(), e.routeAlias(), e.provider(), e.model(),
                                e.streamed(), e.cacheOutcome(), e.similarity(), e.inputTokens(), e.outputTokens(),
                                e.costUsd(), e.savedUsd(), e.ttfbMs(), e.totalMs(), e.status(), e.errorCode(), e.createdAt()))
                        .toList()));
    }
}
