package com.aether.gateway.mockprovider;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side default fault configuration, set via {@code POST
 * /_mock/config} and cleared via {@code POST /_mock/reset}. Exists
 * because acceptance-test scenarios like "mock-primary is configured to
 * return a 503 for every request" (Phase 05/06 Cucumber scenarios) need
 * to configure a mock provider instance's behaviour from outside, since
 * the actual client request travels through the gateway and does not
 * carry provider-specific X-Mock-* headers. Per-request headers, when
 * present, always take precedence over this default.
 */
public class DefaultControlsHolder {

    private final AtomicReference<MockControls> defaultControls = new AtomicReference<>(MockControls.none());

    public MockControls current() {
        return defaultControls.get();
    }

    public void set(MockControls controls) {
        defaultControls.set(controls);
    }

    public void reset() {
        defaultControls.set(MockControls.none());
    }

    /**
     * Per-request headers override the configured default only for the
     * fields they actually specify. A request with no X-Mock-Fail header
     * falls back to the configured default's fail mode/rate; a request
     * that does specify X-Mock-Fail always wins for that field.
     */
    public MockControls resolve(MockControls perRequest) {
        MockControls fallback = defaultControls.get();
        String failMode = perRequest.failMode() != null ? perRequest.failMode() : fallback.failMode();
        Double failRate = perRequest.failMode() != null ? perRequest.failRate() : fallback.failRate();
        return new MockControls(
                perRequest.latencyMs() != null ? perRequest.latencyMs() : fallback.latencyMs(),
                failMode,
                failRate,
                perRequest.streamDelayMs() != null ? perRequest.streamDelayMs() : fallback.streamDelayMs(),
                perRequest.truncateAt() != null ? perRequest.truncateAt() : fallback.truncateAt(),
                perRequest.tokens() != null ? perRequest.tokens() : fallback.tokens());
    }
}
