package com.aether.gateway.admin.web;

import com.aether.gateway.core.domain.ApiKeySummary;
import com.aether.gateway.core.domain.CreatedApiKey;
import com.aether.gateway.core.port.ApiKeyAdminPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Optional;

/** F8.1: full API key CRUD, PRD 13.2. */
@RestController
public class ApiKeysAdminController {

    private final ApiKeyAdminPort apiKeyAdminPort;
    private final Scheduler virtualThreadScheduler;

    public ApiKeysAdminController(ApiKeyAdminPort apiKeyAdminPort, Scheduler virtualThreadScheduler) {
        this.apiKeyAdminPort = apiKeyAdminPort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping("/admin/api-keys")
    public Mono<ResponseEntity<Object>> create(@RequestBody CreateApiKeyRequestDto request) {
        return Mono.fromCallable(() -> apiKeyAdminPort.create(
                        request.name(),
                        request.tags() != null ? request.tags() : List.of(),
                        request.rpsLimit(),
                        request.concurrencyLimit(),
                        request.monthlyTokenBudget()))
                .subscribeOn(virtualThreadScheduler)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body((Object) toCreatedDto(created)));
    }

    @GetMapping("/admin/api-keys")
    public Mono<ResponseEntity<Object>> list() {
        return Mono.fromCallable(apiKeyAdminPort::list)
                .subscribeOn(virtualThreadScheduler)
                .map(keys -> ResponseEntity.ok((Object) keys.stream().map(this::toSummaryDto).toList()));
    }

    @PatchMapping("/admin/api-keys/{id}")
    public Mono<ResponseEntity<Object>> update(@PathVariable("id") String id, @RequestBody UpdateApiKeyRequestDto request) {
        return Mono.fromCallable(() -> apiKeyAdminPort.update(
                        id, request.enabled(), request.rpsLimit(), request.concurrencyLimit(), request.monthlyTokenBudget()))
                .subscribeOn(virtualThreadScheduler)
                .map(this::toUpdateResponse);
    }

    @DeleteMapping("/admin/api-keys/{id}")
    public Mono<ResponseEntity<Object>> delete(@PathVariable("id") String id) {
        return Mono.fromCallable(() -> apiKeyAdminPort.delete(id))
                .subscribeOn(virtualThreadScheduler)
                .map(deleted -> deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build());
    }

    private ResponseEntity<Object> toUpdateResponse(Optional<ApiKeySummary> updated) {
        return updated.<ResponseEntity<Object>>map(summary -> ResponseEntity.ok((Object) toSummaryDto(summary)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private CreatedApiKeyResponseDto toCreatedDto(CreatedApiKey created) {
        return new CreatedApiKeyResponseDto(toSummaryDto(created.summary()), created.rawKey());
    }

    private ApiKeySummaryDto toSummaryDto(ApiKeySummary s) {
        return new ApiKeySummaryDto(
                s.id(), s.name(), s.keyPrefix(), s.tags(), s.rpsLimit(), s.concurrencyLimit(),
                s.monthlyTokenBudget(), s.monthlyUsdBudget(), s.enabled(), s.createdAt());
    }
}
