package com.aether.gateway.mockprovider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts currently-open streaming connections to this mock provider
 * instance. Exists so acceptance tests can measure cancellation
 * propagation timing precisely (PRD's M1 exit criterion: "upstream
 * connection closes within 100 ms"), by polling GET /_mock/active-streams
 * after aborting the client-side stream, rather than inferring closure
 * indirectly from logs.
 */
public class ActiveStreamTracker {

    private final AtomicInteger count = new AtomicInteger(0);

    public void streamStarted() {
        count.incrementAndGet();
    }

    /** Called on stream completion, error, AND cancellation alike (Reactor's doFinally). */
    public void streamFinished() {
        count.decrementAndGet();
    }

    public int count() {
        return count.get();
    }
}
