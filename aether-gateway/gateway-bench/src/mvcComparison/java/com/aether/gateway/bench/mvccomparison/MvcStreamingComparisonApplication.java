package com.aether.gateway.bench.mvccomparison;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Experiment 5 of the M6 benchmarking milestone (docs/design/requirements.md)
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
