package com.aether.gateway.bench.mvccomparison;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PRD section 16.2, experiment 5 / docs/plan/09-milestone-m6-benchmarking.md
 * task 6: a minimal MVC-plus-virtual-threads SSE relay, load-tested
 * against gateway-proxy's real WebFlux streaming path (ADR-002) on the
 * same workload. Not a product artifact - ADR-002's own addendum
 * already evaluated and rejected running a second embedded servlet
 * container inside gateway-proxy itself; this is a standalone,
 * benchmark-only comparison variant, deliberately without gateway-proxy's
 * quota/cache/routing/observability layers (task 6: "a minimal
 * comparison variant"), so it isolates the streaming transport's
 * concurrency model rather than re-implementing the whole product.
 */
@SpringBootApplication
public class MvcStreamingComparisonApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvcStreamingComparisonApplication.class, args);
    }
}
