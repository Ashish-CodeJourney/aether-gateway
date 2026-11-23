package com.aether.gateway.quota.inmemory;

import com.aether.gateway.core.port.IpRateLimitPort;

import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADR-010 lite mode: F9.5's per-IP limit for anonymous requests, in
 * process memory. Anyone can present a new source address, so the
 * number of tracked IPs is capped and the least recently seen one is
 * dropped first; a dropped IP simply starts again with a full bucket.
 */
public class InMemoryIpRateLimitAdapter implements IpRateLimitPort {

    private final int requestsPerSecond;
    private final InstantSource clock;
    private final Map<String, TokenBucket> bucketsByIp;

    public InMemoryIpRateLimitAdapter(int requestsPerSecond, int maxTrackedIps, InstantSource clock) {
        this.requestsPerSecond = requestsPerSecond;
        this.clock = clock;
        this.bucketsByIp = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TokenBucket> eldest) {
                return size() > maxTrackedIps;
            }
        };
    }

    @Override
    public synchronized boolean tryConsume(String ipAddress) {
        Instant now = clock.instant();
        TokenBucket current = bucketsByIp.get(ipAddress);
        TokenBucket bucket = (current == null ? TokenBucket.full(requestsPerSecond, now) : current).refilled(requestsPerSecond, now);
        boolean admitted = bucket.hasToken();
        bucketsByIp.put(ipAddress, admitted ? bucket.withOneTokenTaken() : bucket);
        return admitted;
    }

    synchronized int trackedIpCount() {
        return bucketsByIp.size();
    }
}
