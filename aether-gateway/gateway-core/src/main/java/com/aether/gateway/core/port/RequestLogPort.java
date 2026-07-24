package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.RequestLogEntry;

/**
 * F6.2: driven port for the structured request log. Implementations
 * must never block the caller on the actual persistence - the risk this
 * phase names explicitly is a synchronous write silently regressing the
 * AC1/AC2 latency budgets - so {@code log} is specified as fire-and-
 * forget from the caller's perspective; how the implementation achieves
 * that (a queue plus a background writer, a reactive sink, etc.) is an
 * adapter concern.
 */
public interface RequestLogPort {
    void log(RequestLogEntry entry);
}
