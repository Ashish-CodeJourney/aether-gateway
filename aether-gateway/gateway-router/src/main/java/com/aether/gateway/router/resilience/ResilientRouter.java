package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.FailureClassifier;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.RouteResolver;
import com.aether.gateway.core.domain.TimeoutBudget;
import com.aether.gateway.core.port.ChatCompletionUseCase;
import com.aether.gateway.core.port.ChatStreamUseCase;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.RoutingSource;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * F2 + F3: resolves a route, walks its attempt order (F2.5's weighted
 * members first, then F2.3's strict fallbacks), and for each candidate
 * applies a circuit breaker keyed by (provider, model) (F3.1, not
 * provider alone) plus a bulkhead keyed by provider (F3.4), retrying
 * transient failures with full-jitter backoff up to a cap (F3.3) before
 * failing over to the next chain member (F3.2). A request-level timeout
 * budget is spent across every attempt, including failovers (F3.7).
 * Terminal failures (F3.5) return immediately with no retry or failover.
 */
public class ResilientRouter implements ChatCompletionUseCase, ChatStreamUseCase {

    private final RoutingSource routingSource;
    private final CircuitBreakerRegistry breakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final RetryBackoff retryBackoff;
    private final int maxRetriesPerProvider;
    private final Duration totalTimeoutBudget;
    private final BreakerHintGateway breakerHintGateway;
    private final RouteResolver routeResolver = new RouteResolver();
    private final Random random = new Random();

    public ResilientRouter(
            RoutingSource routingSource,
            CircuitBreakerRegistry breakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            RetryBackoff retryBackoff,
            int maxRetriesPerProvider,
            Duration totalTimeoutBudget,
            BreakerHintGateway breakerHintGateway) {
        this.routingSource = routingSource;
        this.breakerRegistry = breakerRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.retryBackoff = retryBackoff;
        this.maxRetriesPerProvider = maxRetriesPerProvider;
        this.totalTimeoutBudget = totalTimeoutBudget;
        this.breakerHintGateway = breakerHintGateway;
    }

    @Override
    public ProviderResponse complete(ChatCompletionRequest request) {
        RouteConfig route = routingSource.routeFor(request.model()).orElse(null);
        if (route == null) {
            return unknownRoute(request.model());
        }

        List<ChainMember> order = routeResolver.resolveAttemptOrder(route, random);
        TimeoutBudget budget = new TimeoutBudget(totalTimeoutBudget);
        List<String> attempted = new java.util.ArrayList<>();

        for (ChainMember member : order) {
            if (budget.isExhausted()) {
                break;
            }
            // No manual "if OPEN, skip" pre-check here: that would read
            // breaker.getState() once and never call the breaker again
            // for an OPEN member, so it could never observe Resilience4j's
            // own lazy OPEN -> HALF_OPEN transition (which only evaluates
            // when a call is actually attempted) and would stay stuck
            // OPEN forever even after the provider recovers. Instead,
            // attemptWithRetry always calls breaker.executeSupplier(...),
            // and CallNotPermittedException (thrown when the breaker
            // genuinely still blocks calls) is what signals "skip this
            // member", exactly like a real permission check should.
            CircuitBreaker breaker = breakerFor(member);
            ProviderAdapter adapter = routingSource.adapterFor(member.provider()).orElse(null);
            if (adapter == null) {
                continue;
            }
            Bulkhead bulkhead = bulkheadRegistry.bulkhead(member.provider());
            attempted.add(member.provider());

            ProviderResponse outcome = attemptWithRetry(request, member, adapter, breaker, bulkhead, budget, attempted);
            if (outcome != null) {
                return outcome;
            }
            // outcome == null means this chain member is exhausted (retries used up,
            // breaker rejected the call, or bulkhead was full); move to the next member.
        }

        return allUnavailable(request.model(), attempted);
    }

    private ProviderResponse attemptWithRetry(
            ChatCompletionRequest request, ChainMember member, ProviderAdapter adapter,
            CircuitBreaker breaker, Bulkhead bulkhead, TimeoutBudget budget, List<String> attemptedProviders) {

        int attempt = 0;
        while (!budget.isExhausted()) {
            long start = System.nanoTime();
            ProviderResponse response;
            try {
                // adapter.invoke() returns failures as ProviderResponse
                // values, never exceptions (ADR-005-style: the domain
                // models failure as data). Resilience4j's breaker only
                // counts thrown exceptions as failures, so a retryable
                // ProviderError is re-thrown inside the decorated
                // supplier and caught below, purely so the breaker's
                // failure-rate tracking sees it. Terminal errors (not
                // the provider's fault) and successes pass through as
                // plain return values and count as breaker successes.
                Supplier<ProviderResponse> decorated = Bulkhead.decorateSupplier(
                        bulkhead, () -> breaker.executeSupplier(() -> {
                            ProviderResponse r = adapter.invoke(request);
                            if (r instanceof ProviderResponse.ProviderError error
                                    && FailureClassifier.isRetryable(error.httpStatus())) {
                                throw new ProviderCallFailedException(error);
                            }
                            return r;
                        }));
                response = decorated.get();
            } catch (ProviderCallFailedException e) {
                response = e.error;
            } catch (CallNotPermittedException e) {
                budget.spend(elapsedSince(start));
                if (breaker.getState() == CircuitBreaker.State.OPEN) {
                    breakerJustOpened(member);
                }
                return null;
            } catch (BulkheadFullException e) {
                budget.spend(elapsedSince(start));
                return null;
            }
            budget.spend(elapsedSince(start));

            if (response instanceof ProviderResponse.ProviderError error && FailureClassifier.isRetryable(error.httpStatus())) {
                attempt++;
                if (attempt > maxRetriesPerProvider || budget.isExhausted()) {
                    if (breaker.getState() == CircuitBreaker.State.OPEN) {
                        breakerJustOpened(member);
                    }
                    return null;
                }
                sleepUninterruptibly(retryBackoff.delayMillis(attempt, random));
                continue;
            }

            return attributeResponse(response, member.provider(), attempt + 1, attemptedProviders);
        }
        return null;
    }

    /** F6.1/request_log: attaches which provider actually served the request and the failover chain so far, once known. */
    private ProviderResponse attributeResponse(ProviderResponse response, String servedByProvider, int attemptCount, List<String> attemptedProviders) {
        List<String> failoverChain = List.copyOf(attemptedProviders);
        return switch (response) {
            case ProviderResponse.Completion completion ->
                    new ProviderResponse.Completion(completion.response(), servedByProvider, attemptCount, failoverChain);
            case ProviderResponse.ProviderError error ->
                    new ProviderResponse.ProviderError(error.errorCode(), error.message(), error.httpStatus(), error.retryable(), attemptCount, failoverChain);
            case ProviderResponse.StreamChunk chunk -> chunk;
        };
    }

    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> stream(ChatCompletionRequest request) {
        RouteConfig route = routingSource.routeFor(request.model()).orElse(null);
        if (route == null) {
            return errorPublisher();
        }
        List<ChainMember> order = routeResolver.resolveAttemptOrder(route, random);
        for (ChainMember member : order) {
            CircuitBreaker breaker = breakerFor(member);
            // tryAcquirePermission(), not getState(): the former
            // evaluates Resilience4j's lazy OPEN -> HALF_OPEN transition
            // on every call, so a recovered provider is actually
            // re-probed; a stale getState() check would leave the
            // breaker stuck OPEN forever once observed open, same
            // reasoning as in complete() above. The permission is
            // released immediately: the streaming path does not retry or
            // fail over mid-stream (once bytes reach the client,
            // switching providers would duplicate or corrupt output), so
            // there is no matching onSuccess/onError call site here to
            // pair with a held permission; this trades precise breaker
            // accounting on the streaming path for correctness of the
            // open/half-open check itself.
            if (!breaker.tryAcquirePermission()) {
                continue;
            }
            breaker.releasePermission();
            ProviderAdapter adapter = routingSource.adapterFor(member.provider()).orElse(null);
            if (adapter == null) {
                continue;
            }
            return adapter.invokeStreaming(request);
        }
        return errorPublisher();
    }

    private CircuitBreaker breakerFor(ChainMember member) {
        String key = member.provider() + ":" + member.model();
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(key);
        // ADR-004: fast-open on another replica's hint, never fast-close,
        // and ONLY before this breaker has any local call history of its
        // own. A hint's TTL (30s) legitimately outlives a much shorter
        // local waitDurationInOpenState, so consulting it unconditionally
        // on every call would re-force a breaker back OPEN moments after
        // it locally recovers via its own half-open probes, fighting
        // fresher local evidence with a stale cross-replica observation.
        // Restricting the hint to "no local history yet" matches its real
        // purpose: letting a freshly-started replica fast-open before it
        // has gathered any data of its own, never overriding data it has.
        boolean hasLocalHistory = breaker.getMetrics().getNumberOfSuccessfulCalls()
                + breaker.getMetrics().getNumberOfFailedCalls() > 0;
        if (!hasLocalHistory
                && breaker.getState() == CircuitBreaker.State.CLOSED
                && breakerHintGateway != null
                && breakerHintGateway.isHintedOpen(member.provider(), member.model())) {
            breaker.transitionToOpenState();
        }
        return breaker;
    }

    private void breakerJustOpened(ChainMember member) {
        if (breakerHintGateway != null) {
            breakerHintGateway.publishOpen(member.provider(), member.model());
        }
    }

    private ProviderResponse unknownRoute(String model) {
        return new ProviderResponse.ProviderError(
                "unknown_route", "No route configured for model " + model, 400, false);
    }

    private ProviderResponse allUnavailable(String model, List<String> attempted) {
        return new ProviderResponse.ProviderError(
                "no_healthy_provider",
                "All providers unavailable for model " + model + " (attempted: " + attempted + ")",
                502, false, attempted.size(), attempted);
    }

    private Flow.Publisher<ProviderResponse.StreamChunk> errorPublisher() {
        var publisher = new java.util.concurrent.SubmissionPublisher<ProviderResponse.StreamChunk>();
        publisher.closeExceptionally(new IllegalStateException("No healthy provider available for streaming"));
        return publisher;
    }

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
