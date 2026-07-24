package com.aether.gateway.observability;

import com.aether.gateway.core.domain.RequestMetrics;
import com.aether.gateway.core.port.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F6.1: Micrometer implementation of {@link MetricsPort}. Meter names
 * and tags here are the contract the Grafana dashboards
 * (grafana/dashboards/*.json) and prometheus.yml's scrape config are
 * written against; keep them in sync if either side changes.
 */
public class MicrometerMetricsAdapter implements MetricsPort {

    private final MeterRegistry registry;
    private final AtomicInteger inFlightStreams = new AtomicInteger(0);
    // Gauges need a stable backing object whose value Micrometer reads
    // on each scrape; calling MeterRegistry.gauge(name, tags, aNumber)
    // repeatedly with a fresh boxed value does NOT update the gauge
    // (gauges hold a weak reference to the object passed at first
    // registration, not the value), so one AtomicReference per distinct
    // tag combination is cached and registered exactly once, then
    // mutated in place on every subsequent call.
    private final Map<String, AtomicReference<Double>> breakerStateGauges = new ConcurrentHashMap<>();

    public MicrometerMetricsAdapter(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("aether_gateway_in_flight_streams", inFlightStreams);
    }

    @Override
    public void recordRequest(RequestMetrics metrics) {
        Tags tags = Tags.of(
                "provider", nullToUnknown(metrics.provider()),
                "model", nullToUnknown(metrics.model()),
                "route", nullToUnknown(metrics.routeAlias()),
                "cache_outcome", nullToUnknown(metrics.cacheOutcome()),
                "status", nullToUnknown(metrics.status()));

        Counter.builder("aether_gateway_requests_total").tags(tags).register(registry).increment();

        Timer.builder("aether_gateway_request_duration")
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(metrics.totalMs(), TimeUnit.MILLISECONDS);

        Counter.builder("aether_gateway_input_tokens_total").tags(tags).register(registry)
                .increment(metrics.inputTokens());
        Counter.builder("aether_gateway_output_tokens_total").tags(tags).register(registry)
                .increment(metrics.outputTokens());

        Tags costTags = Tags.of(
                "provider", nullToUnknown(metrics.provider()),
                "model", nullToUnknown(metrics.model()),
                "api_key", nullToUnknown(metrics.apiKeyId()));
        Counter.builder("aether_gateway_cost_usd_total").tags(costTags).register(registry)
                .increment(metrics.costUsd());
        Counter.builder("aether_gateway_savings_usd_total").tags(costTags).register(registry)
                .increment(metrics.savedUsd());
    }

    @Override
    public void recordBreakerState(String provider, String model, String state) {
        // Gauges keyed by (provider, model, state) at value 1 when that
        // is the current state, 0 otherwise, is the standard Prometheus
        // pattern for a small enum-valued state exposed as a timeline:
        // one time series per possible state, sum/max over them in the
        // dashboard query to reconstruct "what was the state at time T."
        for (String candidate : new String[] {"CLOSED", "OPEN", "HALF_OPEN"}) {
            String key = nullToUnknown(provider) + "|" + nullToUnknown(model) + "|" + candidate;
            AtomicReference<Double> value = breakerStateGauges.computeIfAbsent(key, k -> {
                var ref = new AtomicReference<>(0.0);
                registry.gauge("aether_gateway_breaker_state",
                        Tags.of("provider", nullToUnknown(provider), "model", nullToUnknown(model), "state", candidate),
                        ref, AtomicReference::get);
                return ref;
            });
            value.set(candidate.equals(state) ? 1.0 : 0.0);
        }
    }

    @Override
    public void streamStarted() {
        inFlightStreams.incrementAndGet();
    }

    @Override
    public void streamEnded() {
        inFlightStreams.decrementAndGet();
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
