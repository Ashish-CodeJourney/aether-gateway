package com.aether.gateway.admin.web;

import com.aether.gateway.core.port.CachePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

/**
 * F4.7/F8.3, PRD 13.2: {@code GET /admin/cache/stats} and
 * {@code DELETE /admin/cache}. Both are scoped to a single namespace
 * per call ({@code namespace}, defaulting to the "anonymous" convention
 * M3/M4 already established for unauthenticated traffic) - the PRD
 * lists these without an explicit namespace parameter, but the cache is
 * namespace-partitioned by design (F4.4's isolation guarantee), so an
 * un-scoped "stats across everyone" call would cross that boundary.
 */
@RestController
public class CacheAdminController {

    private static final String DEFAULT_NAMESPACE = "anonymous";

    private final CachePort cachePort;
    private final Scheduler virtualThreadScheduler;

    public CacheAdminController(CachePort cachePort, Scheduler virtualThreadScheduler) {
        this.cachePort = cachePort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @GetMapping("/admin/cache/stats")
    public Mono<ResponseEntity<Object>> stats(@RequestParam(value = "namespace", required = false) String namespace) {
        String effectiveNamespace = namespace != null ? namespace : DEFAULT_NAMESPACE;
        return Mono.fromCallable(() -> cachePort.countEntries(effectiveNamespace))
                .subscribeOn(virtualThreadScheduler)
                .map(count -> ResponseEntity.ok((Object) new CacheStatsDto(effectiveNamespace, count)));
    }

    @DeleteMapping("/admin/cache")
    public Mono<ResponseEntity<Object>> invalidate(
            @RequestParam(value = "namespace", required = false) String namespace,
            @RequestParam(value = "prefix", required = false, defaultValue = "") String prefix) {
        String effectiveNamespace = namespace != null ? namespace : DEFAULT_NAMESPACE;
        return Mono.fromCallable(() -> cachePort.invalidateByPrefix(effectiveNamespace, prefix))
                .subscribeOn(virtualThreadScheduler)
                .map(removed -> ResponseEntity.ok((Object) Map.of("namespace", effectiveNamespace, "removed", removed)));
    }
}
