package com.aether.gateway.proxy.web;

import com.aether.gateway.core.domain.ApiKeyContext;
import com.aether.gateway.core.domain.QuotaDecision;
import com.aether.gateway.core.port.ApiKeyLookupPort;
import com.aether.gateway.core.port.IpRateLimitPort;
import com.aether.gateway.core.port.QuotaPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Authentication and quota admission for a {@code /v1/*} request, with the
 * same policy {@link ChatCompletionController} applies: a recognised key
 * reserves against its own quota; no key, or an unrecognised one (treated
 * identically, so the endpoint is no key oracle), is refused unless
 * anonymous access is enabled, in which case only the per-IP limit
 * applies. Extracted for the embeddings endpoint; the chat controller
 * still carries its own copy until it can be migrated without colliding
 * with in-flight chat-path work.
 */
@Component
public class RequestAdmission {

    public sealed interface Admission {
        record Unmetered() implements Admission {
        }

        record Admitted(ApiKeyContext key, QuotaDecision.Allowed allowed) implements Admission {
        }

        /** {@code remainingQuota} is null unless the refusal came from a key's own quota. */
        record Refused(int status, String code, String detail, Long remainingQuota) implements Admission {
        }
    }

    private final ApiKeyLookupPort apiKeyLookupPort;
    private final QuotaPort quotaPort;
    private final IpRateLimitPort ipRateLimitPort;
    private final boolean allowAnonymous;

    public RequestAdmission(
            ApiKeyLookupPort apiKeyLookupPort,
            QuotaPort quotaPort,
            IpRateLimitPort ipRateLimitPort,
            @Value("${aether.security.allow-anonymous:false}") boolean allowAnonymous) {
        this.apiKeyLookupPort = apiKeyLookupPort;
        this.quotaPort = quotaPort;
        this.ipRateLimitPort = ipRateLimitPort;
        this.allowAnonymous = allowAnonymous;
    }

    public Admission admit(String authorization, String clientIp, long estimatedTokens) {
        if (authorization == null || authorization.isBlank()) {
            return anonymous(clientIp);
        }
        String rawKey = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return apiKeyLookupPort.findByRawKey(rawKey)
                .map(key -> reserve(key, estimatedTokens))
                .orElseGet(() -> anonymous(clientIp));
    }

    /** Replaces the reservation with actual usage and frees the concurrency slot; a no-op for unmetered or refused requests. */
    public void settle(Admission admission, long actualTokens) {
        if (admission instanceof Admission.Admitted admitted) {
            quotaPort.reconcile(admitted.key().keyId(), admitted.allowed().requestId(), actualTokens);
            quotaPort.releaseConcurrencySlot(admitted.key().keyId(), admitted.allowed().requestId());
        }
    }

    private Admission reserve(ApiKeyContext key, long estimatedTokens) {
        return switch (quotaPort.checkAndReserve(key, UUID.randomUUID().toString(), estimatedTokens)) {
            case QuotaDecision.Allowed allowed -> new Admission.Admitted(key, allowed);
            case QuotaDecision.Rejected rejected ->
                    new Admission.Refused(429, rejected.reason(), "Quota exceeded: " + rejected.reason(), rejected.remainingMonthlyTokens());
        };
    }

    private Admission anonymous(String clientIp) {
        if (!allowAnonymous) {
            return new Admission.Refused(401, "unauthenticated",
                    "A valid API key is required. Send it as 'Authorization: Bearer <key>'.", null);
        }
        return ipRateLimitPort.tryConsume(clientIp)
                ? new Admission.Unmetered()
                : new Admission.Refused(429, "ip_rate_limited", "Too many unauthenticated requests from this address", null);
    }
}
