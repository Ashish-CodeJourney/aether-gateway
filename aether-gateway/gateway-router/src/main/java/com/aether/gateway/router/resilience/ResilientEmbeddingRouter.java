package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.FailureClassifier;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.RouteResolver;
import com.aether.gateway.core.domain.TimeoutBudget;
import com.aether.gateway.core.port.EmbeddingUseCase;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.RoutingSource;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The embeddings counterpart of {@link ResilientRouter}: same route
 * resolution, same (provider, model)-keyed breakers and per-provider
 * bulkheads (shared registries, so a provider's health is one fact across
 * both endpoints), same full-jitter retries and request-wide timeout
 * budget. Kept as a sibling rather than generalising ResilientRouter so
 * the chat path stays untouched while it is being extended in parallel.
 *
 * <p>Two embeddings-specific rules: a chain member whose provider has no
 * embeddings API is skipped (not counted against its breaker), and if no
 * member supports embeddings at all the caller gets a 400 naming the
 * route, since retrying can never help. {@code attemptCount} counts every
 * provider call made across the whole chain.
 */
public class ResilientEmbeddingRouter implements EmbeddingUseCase {

    private final RoutingSource routingSource;
    private final CircuitBreakerRegistry breakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final RetryBackoff retryBackoff;
    private final int maxRetriesPerProvider;
    private final Duration totalTimeoutBudget;
    private final BreakerHintGateway breakerHintGateway;
    private final RouteResolver routeResolver = new RouteResolver();
    private final Random random = new Random();

    public ResilientEmbeddingRouter(
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

    private static final class Attempts {
        private final List<String> providers = new ArrayList<>();
        private int calls;
        private boolean anySupported;
    }

    @Override
    public EmbeddingOutcome embed(EmbeddingRequest request) {
        Optional<RouteConfig> route = routingSource.routeFor(request.model());
        if (route.isEmpty()) {
            return failure("unknown_route", "No route configured for model " + request.model(), 400, List.of(), 0);
        }

        TimeoutBudget budget = new TimeoutBudget(totalTimeoutBudget);
        Attempts attempts = new Attempts();
        for (ChainMember member : routeResolver.resolveAttemptOrder(route.get(), random)) {
            if (budget.isExhausted()) {
                break;
            }
            Optional<ProviderAdapter> adapter = routingSource.adapterFor(member.provider());
            if (adapter.isEmpty()) {
                continue;
            }
            attempts.providers.add(member.provider());
            Optional<EmbeddingOutcome> outcome = attemptMember(request.withModel(member.model()), member, adapter.get(), budget, attempts);
            if (outcome.isPresent()) {
                return outcome.get();
            }
        }

        if (!attempts.anySupported && !attempts.providers.isEmpty()) {
            return failure(EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED,
                    "Route " + request.model() + " has no provider that supports embeddings (attempted: " + attempts.providers + ")",
                    400, attempts.providers, attempts.calls);
        }
        return failure("no_healthy_provider",
                "All providers unavailable for model " + request.model() + " (attempted: " + attempts.providers + ")",
                502, attempts.providers, attempts.calls);
    }

    /** Empty means "this member is exhausted or cannot serve embeddings; move on to the next one". */
    private Optional<EmbeddingOutcome> attemptMember(
            EmbeddingRequest downstreamRequest, ChainMember member, ProviderAdapter adapter, TimeoutBudget budget, Attempts attempts) {
        CircuitBreaker breaker = breakerFor(member);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(member.provider());
        int retries = 0;
        while (!budget.isExhausted()) {
            long start = System.nanoTime();
            EmbeddingOutcome outcome;
            try {
                // Retryable failures are thrown inside the decorated call
                // purely so the breaker counts them, as in ResilientRouter.
                Supplier<EmbeddingOutcome> decorated = Bulkhead.decorateSupplier(bulkhead, () -> breaker.executeSupplier(() -> {
                    attempts.calls++;
                    EmbeddingOutcome result = adapter.embed(downstreamRequest);
                    if (result instanceof EmbeddingOutcome.Failure failure && FailureClassifier.isRetryable(failure.error().httpStatus())) {
                        throw new ProviderCallFailedException(failure.error());
                    }
                    return result;
                }));
                outcome = decorated.get();
            } catch (ProviderCallFailedException e) {
                outcome = new EmbeddingOutcome.Failure(e.error);
            } catch (CallNotPermittedException e) {
                budget.spend(elapsedSince(start));
                publishIfOpen(breaker, member);
                return Optional.empty();
            } catch (BulkheadFullException e) {
                budget.spend(elapsedSince(start));
                return Optional.empty();
            }
            budget.spend(elapsedSince(start));

            if (outcome instanceof EmbeddingOutcome.Failure failure && failure.isUnsupported()) {
                return Optional.empty();
            }
            attempts.anySupported = true;
            if (outcome instanceof EmbeddingOutcome.Failure failure && FailureClassifier.isRetryable(failure.error().httpStatus())) {
                retries++;
                if (retries > maxRetriesPerProvider || budget.isExhausted()) {
                    publishIfOpen(breaker, member);
                    return Optional.empty();
                }
                sleepUninterruptibly(retryBackoff.delayMillis(retries, random));
                continue;
            }
            return Optional.of(attribute(outcome, member, attempts));
        }
        return Optional.empty();
    }

    private EmbeddingOutcome attribute(EmbeddingOutcome outcome, ChainMember member, Attempts attempts) {
        List<String> chain = List.copyOf(attempts.providers);
        return switch (outcome) {
            case EmbeddingOutcome.Success success ->
                    new EmbeddingOutcome.Success(success.response(), member.provider(), member.model(), attempts.calls, chain);
            case EmbeddingOutcome.Failure failure -> {
                ProviderResponse.ProviderError error = failure.error();
                yield failure(error.errorCode(), error.message(), error.httpStatus(), chain, attempts.calls);
            }
        };
    }

    private CircuitBreaker breakerFor(ChainMember member) {
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(member.provider() + ":" + member.model());
        // ADR-004, as in ResilientRouter: a cross-replica hint may only
        // fast-open a breaker that has no local history of its own yet.
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

    private void publishIfOpen(CircuitBreaker breaker, ChainMember member) {
        if (breakerHintGateway != null && breaker.getState() == CircuitBreaker.State.OPEN) {
            breakerHintGateway.publishOpen(member.provider(), member.model());
        }
    }

    private static EmbeddingOutcome.Failure failure(String code, String message, int status, List<String> chain, int calls) {
        return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError(code, message, status, false, calls, chain));
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
