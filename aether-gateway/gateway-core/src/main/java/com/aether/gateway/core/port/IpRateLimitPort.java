package com.aether.gateway.core.port;

/**
 * F9.5: driven port for per-IP rate limiting on unauthenticated
 * requests specifically - the "anonymous" path (no Authorization
 * header, or an unrecognised one) that {@link QuotaPort}'s per-API-key
 * enforcement never sees at all, since it has no key to key state by.
 */
public interface IpRateLimitPort {

    /** Returns true if this IP is still within its rate limit (and the attempt is consumed); false if it should be rejected. */
    boolean tryConsume(String ipAddress);
}
