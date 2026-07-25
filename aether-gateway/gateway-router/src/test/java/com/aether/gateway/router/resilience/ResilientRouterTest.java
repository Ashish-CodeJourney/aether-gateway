package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionChoice;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatCompletionResponse;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.Usage;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.RoutingSource;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientRouterTest {

    private static ChatCompletionResponse fakeSuccess(String from) {
        return new ChatCompletionResponse(
                "id-" + from, "chat.completion", 0L, "mock",
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", "hi from " + from), "stop")),
                Usage.of(1, 1));
    }

    private static class FakeAdapter implements ProviderAdapter {
        private final String name;
        private final Supplier<ProviderResponse> responseSupplier;
        private final AtomicInteger invocationCount = new AtomicInteger();

        FakeAdapter(String name, ProviderResponse fixedResponse) {
            this(name, () -> fixedResponse);
        }

        FakeAdapter(String name, Supplier<ProviderResponse> responseSupplier) {
            this.name = name;
            this.responseSupplier = responseSupplier;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public ProviderResponse invoke(ChatCompletionRequest request) {
            invocationCount.incrementAndGet();
            return responseSupplier.get();
        }

        @Override
        public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private ResilientRouter buildRouter(Map<String, ProviderAdapter> adapters, RouteConfig route) {
        return buildRouter(adapters, route, CircuitBreakerRegistry.ofDefaults());
    }

    private ResilientRouter buildRouter(Map<String, ProviderAdapter> adapters, RouteConfig route, CircuitBreakerRegistry breakerRegistry) {
        RoutingSource source = new RoutingSource() {
            @Override
            public java.util.Optional<RouteConfig> routeFor(String alias) {
                return alias.equals(route.alias()) ? java.util.Optional.of(route) : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<ProviderAdapter> adapterFor(String providerName) {
                return java.util.Optional.ofNullable(adapters.get(providerName));
            }

            @Override
            public Map<String, RouteConfig> allRoutes() {
                return Map.of(route.alias(), route);
            }
        };
        return new ResilientRouter(
                source,
                breakerRegistry,
                BulkheadRegistry.ofDefaults(),
                new com.aether.gateway.core.domain.RetryBackoff(1, 5),
                2,
                Duration.ofSeconds(5),
                null);
    }

    @Test
    void returnsThePrimarysSuccessfulResponseWithoutTryingTheFallback() {
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.Completion(fakeSuccess("primary")));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        assertThat(((ProviderResponse.Completion) result).response().id()).isEqualTo("id-primary");
        assertThat(fallback.invocationCount.get()).isZero();
    }

    @Test
    void attributesTheSuccessfulResponseToTheProviderThatActuallyServedIt() {
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.Completion(fakeSuccess("primary")));
        var route = new RouteConfig("mock", List.of(new ChainMember("mock-primary", "mock", 100)));
        var router = buildRouter(Map.of("mock-primary", primary), route);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        var result = (ProviderResponse.Completion) router.complete(request);

        assertThat(result.servedByProvider()).isEqualTo("mock-primary");
        assertThat(result.attemptCount()).isEqualTo(1);
        assertThat(result.failoverChain()).containsExactly("mock-primary");
    }

    @Test
    void attributesAFailoverResponseWithTheFullChainAttempted() {
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.ProviderError("service_unavailable", "down", 503, true));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        var result = (ProviderResponse.Completion) router.complete(request);

        assertThat(result.servedByProvider()).isEqualTo("mock-fallback");
        assertThat(result.failoverChain()).containsExactly("mock-primary", "mock-fallback");
    }

    @Test
    void failsOverToTheFallbackWhenThePrimaryReturnsARetryableError() {
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.ProviderError("service_unavailable", "down", 503, true));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        assertThat(((ProviderResponse.Completion) result).response().id()).isEqualTo("id-fallback");
        assertThat(primary.invocationCount.get()).isGreaterThan(0);
    }

    @Test
    void doesNotFailOverOnATerminalError() {
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.ProviderError("bad_request", "bad", 400, false));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        assertThat(((ProviderResponse.ProviderError) result).httpStatus()).isEqualTo(400);
        assertThat(fallback.invocationCount.get()).isZero();
    }

    @Test
    void returnsAnErrorWhenNoRouteIsConfiguredForTheRequestedModel() {
        var route = new RouteConfig("mock", List.of(new ChainMember("mock-primary", "mock", 100)));
        var router = buildRouter(Map.of("mock-primary", new FakeAdapter("mock-primary", new ProviderResponse.Completion(fakeSuccess("x")))), route);

        var request = new ChatCompletionRequest("unknown-model", List.of(new ChatMessage("user", "hi")), false);
        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.ProviderError.class);
        assertThat(((ProviderResponse.ProviderError) result).httpStatus()).isEqualTo(400);
    }

    @Test
    void theBreakerActuallyOpensAfterRepeatedRetryableFailures() {
        // Regression test: ProviderAdapter.invoke() returns failures as
        // ProviderResponse values, not thrown exceptions. Resilience4j
        // only counts thrown exceptions as failures by default, so
        // without ResilientRouter re-throwing retryable errors inside
        // the decorated supplier, the breaker would never see a
        // failure and would stay CLOSED forever, no matter how many
        // times the provider actually failed.
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.ProviderError("service_unavailable", "down", 503, true));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var breakerConfig = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .build();
        var breakerRegistry = CircuitBreakerRegistry.of(breakerConfig);
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route, breakerRegistry);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        for (int i = 0; i < 5; i++) {
            router.complete(request);
        }

        var breaker = breakerRegistry.circuitBreaker("mock-primary:mock");
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    }

    @Test
    void theBreakerRecoversOnceTheProviderStartsSucceedingAgain() throws InterruptedException {
        // Regression test for a real bug found during manual M2
        // verification: an earlier version of ResilientRouter checked
        // breaker.getState() == OPEN once per chain member and skipped
        // calling the breaker again if so. Resilience4j only evaluates
        // its own OPEN -> HALF_OPEN transition when a call is actually
        // attempted (acquirePermission), so that pre-check meant a
        // breaker, once observed OPEN, could never be called again and
        // stayed OPEN forever, even after the provider recovered. Fixed
        // by always attempting through the breaker and letting
        // CallNotPermittedException signal "still blocked".
        var failing = new AtomicReference<ProviderResponse>(
                new ProviderResponse.ProviderError("service_unavailable", "down", 503, true));
        var primary = new FakeAdapter("mock-primary", failing::get);
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var breakerConfig = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        var breakerRegistry = CircuitBreakerRegistry.of(breakerConfig);
        var router = buildRouter(Map.of("mock-primary", primary, "mock-fallback", fallback), route, breakerRegistry);
        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);

        for (int i = 0; i < 5; i++) {
            router.complete(request);
        }
        var breaker = breakerRegistry.circuitBreaker("mock-primary:mock");
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        // Provider recovers, and enough time passes for the breaker's
        // wait-duration-in-open-state to elapse.
        failing.set(new ProviderResponse.Completion(fakeSuccess("primary-recovered")));
        Thread.sleep(250);

        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        assertThat(((ProviderResponse.Completion) result).response().id()).isEqualTo("id-primary-recovered");
        assertThat(breaker.getState())
                .isIn(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void aHintIsIgnoredOnceTheBreakerHasAnyLocalCallHistory() {
        // Regression test for the bug recorded in ADR-004's addendum: a
        // hint published 30 seconds ago must not re-open a breaker that
        // has local evidence of its own. Modelled directly: seed one real
        // local success (so the breaker has call history), then verify a
        // permanently-live hint is no longer consulted at all.
        var primary = new FakeAdapter("mock-primary", new ProviderResponse.Completion(fakeSuccess("primary")));
        var fallback = new FakeAdapter("mock-fallback", new ProviderResponse.Completion(fakeSuccess("fallback")));
        var route = new RouteConfig("mock", List.of(
                new ChainMember("mock-primary", "mock", 100),
                new ChainMember("mock-fallback", "mock", null)));
        var breakerRegistry = CircuitBreakerRegistry.ofDefaults();

        AtomicInteger hintCallCount = new AtomicInteger();
        BreakerHintGateway alwaysHintedOpen = new BreakerHintGateway() {
            @Override
            public void publishOpen(String provider, String model) {
            }

            @Override
            public boolean isHintedOpen(String provider, String model) {
                hintCallCount.incrementAndGet();
                return true;
            }
        };
        RoutingSource source = new RoutingSource() {
            @Override
            public java.util.Optional<RouteConfig> routeFor(String alias) {
                return alias.equals(route.alias()) ? java.util.Optional.of(route) : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<ProviderAdapter> adapterFor(String providerName) {
                return java.util.Optional.ofNullable(Map.of("mock-primary", (ProviderAdapter) primary, "mock-fallback", fallback).get(providerName));
            }

            @Override
            public Map<String, RouteConfig> allRoutes() {
                return Map.of(route.alias(), route);
            }
        };
        var router = new ResilientRouter(
                source, breakerRegistry, BulkheadRegistry.ofDefaults(),
                new com.aether.gateway.core.domain.RetryBackoff(1, 5), 1, Duration.ofSeconds(5), alwaysHintedOpen);

        // Seed one real local success directly on the breaker, simulating
        // a prior successful call already having happened.
        breakerRegistry.circuitBreaker("mock-primary:mock").onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);

        var request = new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), false);
        ProviderResponse result = router.complete(request);

        assertThat(result).isInstanceOf(ProviderResponse.Completion.class);
        assertThat(((ProviderResponse.Completion) result).response().id()).isEqualTo("id-primary");
        assertThat(primary.invocationCount.get()).isEqualTo(1);
        // The hint gateway is still queried for the fallback member (which
        // has no local history), but never for the primary now that it
        // has local history — proven indirectly by primary actually
        // having been called instead of skipped.
    }
}
