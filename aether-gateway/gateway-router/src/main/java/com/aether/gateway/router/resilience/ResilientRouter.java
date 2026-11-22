package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.AttributedStream;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.FailureClassifier;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.RouteResolver;
import com.aether.gateway.core.domain.StreamAttribution;
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
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
                ChatCompletionRequest downstreamRequest = request.withModel(member.model());
                Supplier<ProviderResponse> decorated = Bulkhead.decorateSupplier(
                        bulkhead, () -> breaker.executeSupplier(() -> {
                            ProviderResponse r = adapter.invoke(downstreamRequest);
                            if (r instanceof ProviderResponse.ProviderError error && shouldFailOver(error)) {
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

            if (response instanceof ProviderResponse.ProviderError error && shouldFailOver(error)) {
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

    /**
     * Streaming counterpart of {@link #complete}: the same chain walk,
     * breakers, bulkheads, retries and timeout budget, applied only up to
     * a stream's first chunk. A retryable {@link ProviderStreamException}
     * before that moves on exactly as a retryable {@code ProviderError}
     * would; once a chunk has reached the caller the stream is committed
     * to that provider, because switching mid-stream would duplicate or
     * corrupt output already sent to the client.
     *
     * <p>The first attempt is started eagerly so an adapter refusing the
     * request outright (e.g. {@code ToolsNotSupportedException}) still
     * throws from this call; retries and failovers run on subscription.
     */
    @Override
    public Flow.Publisher<ProviderResponse.StreamChunk> stream(ChatCompletionRequest request) {
        AtomicReference<StreamAttribution> attribution = new AtomicReference<>();
        RouteConfig route = routingSource.routeFor(request.model()).orElse(null);
        Flux<ProviderResponse.StreamChunk> chunks = route == null
                ? unknownRouteStream(request.model(), attribution)
                : new StreamWalk(request, routeResolver.resolveAttemptOrder(route, random), attribution).fromMember(0);
        return new RoutedStream(JdkFlowAdapter.publisherToFlowPublisher(chunks), attribution);
    }

    private Flux<ProviderResponse.StreamChunk> unknownRouteStream(String model, AtomicReference<StreamAttribution> attribution) {
        ProviderResponse.ProviderError error = unknownRoute(model);
        attribution.set(new StreamAttribution(null, error.attemptCount(), error.failoverChain()));
        return Flux.error(new ProviderStreamException(error.httpStatus(), error.errorCode(), error.message(), false));
    }

    private record RoutedStream(
            Flow.Publisher<ProviderResponse.StreamChunk> chunks,
            AtomicReference<StreamAttribution> settled) implements AttributedStream {

        @Override
        public void subscribe(Flow.Subscriber<? super ProviderResponse.StreamChunk> subscriber) {
            chunks.subscribe(subscriber);
        }

        @Override
        public Optional<StreamAttribution> attribution() {
            return Optional.ofNullable(settled.get());
        }
    }

    /** One request's walk along its chain; members are tried one after another, never concurrently. */
    private final class StreamWalk {
        private final ChatCompletionRequest request;
        private final List<ChainMember> order;
        private final AtomicReference<StreamAttribution> attribution;
        private final TimeoutBudget budget = new TimeoutBudget(totalTimeoutBudget);
        private final List<String> attempted = new ArrayList<>();

        StreamWalk(ChatCompletionRequest request, List<ChainMember> order, AtomicReference<StreamAttribution> attribution) {
            this.request = request;
            this.order = order;
            this.attribution = attribution;
        }

        Flux<ProviderResponse.StreamChunk> fromMember(int index) {
            if (index >= order.size() || budget.isExhausted()) {
                return allMembersFailed();
            }
            ChainMember member = order.get(index);
            CircuitBreaker breaker = breakerFor(member);
            ProviderAdapter adapter = routingSource.adapterFor(member.provider()).orElse(null);
            if (adapter == null) {
                return fromMember(index + 1);
            }
            attempted.add(member.provider());
            return attempt(index, member, adapter, breaker, 1);
        }

        private Flux<ProviderResponse.StreamChunk> attempt(
                int index, ChainMember member, ProviderAdapter adapter, CircuitBreaker breaker, int attemptNumber) {
            if (budget.isExhausted()) {
                return allMembersFailed();
            }
            Bulkhead bulkhead = bulkheadRegistry.bulkhead(member.provider());
            if (!bulkhead.tryAcquirePermission()) {
                return fromMember(index + 1);
            }
            // tryAcquirePermission(), not getState(): it evaluates
            // Resilience4j's lazy OPEN -> HALF_OPEN transition, so a
            // recovered provider is re-probed (see complete()).
            if (!breaker.tryAcquirePermission()) {
                bulkhead.onComplete();
                if (breaker.getState() == CircuitBreaker.State.OPEN) {
                    breakerJustOpened(member);
                }
                return fromMember(index + 1);
            }
            StreamAttempt streamAttempt = new StreamAttempt(breaker, bulkhead);
            Flow.Publisher<ProviderResponse.StreamChunk> upstream;
            try {
                upstream = adapter.invokeStreaming(request.withModel(member.model()));
            } catch (RuntimeException e) {
                streamAttempt.abandon();
                throw e;
            }
            return JdkFlowAdapter.flowPublisherToFlux(upstream)
                    .doOnNext(chunk -> {
                        if (streamAttempt.firstChunk()) {
                            budget.spend(streamAttempt.elapsed());
                            streamAttempt.succeed();
                            settle(member.provider(), attemptNumber);
                        }
                    })
                    .doOnComplete(() -> {
                        if (!streamAttempt.sawChunk()) {
                            budget.spend(streamAttempt.elapsed());
                            streamAttempt.succeed();
                            settle(member.provider(), attemptNumber);
                        }
                    })
                    .doOnTerminate(streamAttempt::releaseBulkhead)
                    .doOnCancel(streamAttempt::abandon)
                    .onErrorResume(failure -> !streamAttempt.sawChunk(),
                            failure -> afterFailureBeforeFirstChunk(failure, index, member, adapter, breaker, attemptNumber, streamAttempt));
        }

        private Flux<ProviderResponse.StreamChunk> afterFailureBeforeFirstChunk(
                Throwable failure, int index, ChainMember member, ProviderAdapter adapter, CircuitBreaker breaker,
                int attemptNumber, StreamAttempt streamAttempt) {
            budget.spend(streamAttempt.elapsed());
            if (!(failure instanceof ProviderStreamException providerFailure)) {
                streamAttempt.fail(failure);
                settle(null, attemptNumber);
                return Flux.error(failure);
            }
            if (!providerFailure.retryable()) {
                // Not the provider's fault: counts as a breaker success,
                // as a terminal ProviderError does in complete().
                streamAttempt.succeed();
                settle(null, attemptNumber);
                return Flux.error(providerFailure);
            }
            streamAttempt.fail(providerFailure);
            if (attemptNumber > maxRetriesPerProvider || budget.isExhausted()) {
                if (breaker.getState() == CircuitBreaker.State.OPEN) {
                    breakerJustOpened(member);
                }
                return Flux.defer(() -> fromMember(index + 1));
            }
            return Mono.delay(Duration.ofMillis(retryBackoff.delayMillis(attemptNumber, random)))
                    .thenMany(Flux.defer(() -> attempt(index, member, adapter, breaker, attemptNumber + 1)));
        }

        private Flux<ProviderResponse.StreamChunk> allMembersFailed() {
            ProviderResponse.ProviderError error = allUnavailable(request.model(), attempted);
            attribution.set(new StreamAttribution(null, error.attemptCount(), error.failoverChain()));
            return Flux.error(new ProviderStreamException(error.httpStatus(), error.errorCode(), error.message(), false));
        }

        private void settle(String servedByProvider, int attemptCount) {
            attribution.set(new StreamAttribution(servedByProvider, attemptCount, attempted));
        }
    }

    /**
     * The breaker permission and bulkhead slot held by one streaming
     * attempt. Each is given back exactly once, whichever of first chunk,
     * completion, failure or cancellation comes first.
     */
    private static final class StreamAttempt {
        private final CircuitBreaker breaker;
        private final Bulkhead bulkhead;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean chunkSeen = new AtomicBoolean();
        private final AtomicBoolean breakerSettled = new AtomicBoolean();
        private final AtomicBoolean bulkheadReleased = new AtomicBoolean();

        StreamAttempt(CircuitBreaker breaker, Bulkhead bulkhead) {
            this.breaker = breaker;
            this.bulkhead = bulkhead;
        }

        boolean firstChunk() {
            return chunkSeen.compareAndSet(false, true);
        }

        boolean sawChunk() {
            return chunkSeen.get();
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }

        void succeed() {
            if (breakerSettled.compareAndSet(false, true)) {
                breaker.onSuccess(elapsed().toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        void fail(Throwable failure) {
            if (breakerSettled.compareAndSet(false, true)) {
                breaker.onError(elapsed().toNanos(), TimeUnit.NANOSECONDS, failure);
            }
        }

        void abandon() {
            if (breakerSettled.compareAndSet(false, true)) {
                breaker.releasePermission();
            }
            releaseBulkhead();
        }

        void releaseBulkhead() {
            if (bulkheadReleased.compareAndSet(false, true)) {
                bulkhead.onComplete();
            }
        }
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

    private ProviderResponse.ProviderError unknownRoute(String model) {
        return new ProviderResponse.ProviderError(
                "unknown_route", "No route configured for model " + model, 400, false);
    }

    private ProviderResponse.ProviderError allUnavailable(String model, List<String> attempted) {
        return new ProviderResponse.ProviderError(
                "no_healthy_provider",
                "All providers unavailable for model " + model + " (attempted: " + attempted + ")",
                502, false, attempted.size(), attempted);
    }

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /**
     * A provider failure worth retrying elsewhere: one of the statuses
     * every provider uses for "try again" (429/503/504), or one the
     * adapter itself knows is transient for its provider - an
     * unreachable host (502) or Anthropic's 529 overloaded.
     */
    private static boolean shouldFailOver(ProviderResponse.ProviderError error) {
        return FailureClassifier.isRetryable(error.httpStatus()) || error.retryable();
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
