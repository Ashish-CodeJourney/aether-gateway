package com.aether.gateway.mockprovider;

/**
 * Parsed form of the PRD section 14 control headers. {@code streamDelayMs}
 * and {@code truncateAt} are accepted from Phase 03 but stay inert until
 * Phase 04 wires streaming.
 */
public record MockControls(
        Long latencyMs,
        String failMode,
        Double failRate,
        Integer streamDelayMs,
        Integer truncateAt,
        Integer tokens) {

    public static MockControls none() {
        return new MockControls(null, null, null, null, null, null);
    }
}
