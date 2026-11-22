package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.AttributedStream;
import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.ProviderStreamException;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.domain.StreamAttribution;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.RoutingSource;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientRouterStreamingTest {

    private static final int MAX_RETRIES_PER_PROVIDER = 1;

    private static ProviderResponse.StreamChunk chunk(String content) {
        return new ProviderResponse.StreamChunk("s", 0, content, false);
    }

    private static Flux<ProviderResponse.StreamChunk> serving(String... contents) {
        return Flux.fromArray(contents).map(ResilientRouterStreamingTest::chunk);
    }

    private static Flux<ProviderResponse.StreamChunk> failing(int status, boolean retryable) {
        return Flux.error(new ProviderStreamException(status, "provider_error", status + " from upstream", retryable));
    }

    private static final class StreamingAdapter implements ProviderAdapter {
        private final String name;
        private final Supplier<Flux<ProviderResponse.StreamChunk>> streams;
        private final AtomicInteger streamInvocations = new AtomicInteger();

        StreamingAdapter(String name, Supplier<Flux<ProviderResponse.StreamChunk>> streams) {
            this.name = name;
            this.streams = streams;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public ProviderResponse invoke(ChatCompletionRequest request) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
            streamInvocations.incrementAndGet();
            return JdkFlowAdapter.publisherToFlowPublisher(streams.get());
        }
    }

    private record Outcome(List<String> contents, Throwable error) {
    }

    private static Outcome drain(Flow.Publisher<ProviderResponse.StreamChunk> publisher) {
        List<String> contents = new CopyOnWriteArrayList<>();
        Throwable[] error = new Throwable[1];
        JdkFlowAdapter.flowPublisherToFlux(publisher)
                .doOnNext(c -> contents.add(c.deltaContent()))
                .onErrorResume(e -> {
                    error[0] = e;
                    return Flux.empty();
                })
                .blockLast(Duration.ofSeconds(5));
        return new Outcome(List.copyOf(contents), error[0]);
    }

    private static final RouteConfig PRIMARY_THEN_FALLBACK = new RouteConfig("mock", List.of(
            new ChainMember("mock-primary", "mock", 100),
            new ChainMember("mock-fallback", "mock", null)));

    private static ChatCompletionRequest streamingRequest() {
        return new ChatCompletionRequest("mock", List.of(new ChatMessage("user", "hi")), true);
    }

    private static ResilientRouter router(
            Map<String, ProviderAdapter> adapters, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads) {
        RoutingSource source = new RoutingSource() {
            @Override
            public Optional<RouteConfig> routeFor(String alias) {
                return alias.equals(PRIMARY_THEN_FALLBACK.alias()) ? Optional.of(PRIMARY_THEN_FALLBACK) : Optional.empty();
            }

            @Override
            public Optional<ProviderAdapter> adapterFor(String providerName) {
                return Optional.ofNullable(adapters.get(providerName));
            }

            @Override
            public Map<String, RouteConfig> allRoutes() {
                return Map.of(PRIMARY_THEN_FALLBACK.alias(), PRIMARY_THEN_FALLBACK);
            }
        };
        return new ResilientRouter(source, breakers, bulkheads, new RetryBackoff(1, 5),
                MAX_RETRIES_PER_PROVIDER, Duration.ofSeconds(5), null);
    }

    private static ResilientRouter router(StreamingAdapter primary, StreamingAdapter fallback) {
        return router(primary, fallback, CircuitBreakerRegistry.ofDefaults());
    }

    private static ResilientRouter router(StreamingAdapter primary, StreamingAdapter fallback, CircuitBreakerRegistry breakers) {
        return router(Map.of("mock-primary", primary, "mock-fallback", fallback), breakers, BulkheadRegistry.ofDefaults());
    }

    private static StreamAttribution attributionOf(Flow.Publisher<ProviderResponse.StreamChunk> publisher) {
        assertThat(publisher).isInstanceOf(AttributedStream.class);
        return ((AttributedStream) publisher).attribution().orElseThrow();
    }

    @Test
    void failsOverToTheFallbackWhenThePrimaryFailsRetryablyBeforeItsFirstChunk() {
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("Hel", "lo"));

        Outcome outcome = drain(router(primary, fallback).stream(streamingRequest()));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.contents()).containsExactly("Hel", "lo");
        assertThat(primary.streamInvocations.get()).isEqualTo(MAX_RETRIES_PER_PROVIDER + 1);
        assertThat(fallback.streamInvocations.get()).isEqualTo(1);
    }

    @Test
    void failsOverWhenThePrimaryIsUnreachable() {
        var primary = new StreamingAdapter("mock-primary", () -> Flux.error(
                new ProviderStreamException(502, "provider_unreachable", "Connection refused", true)));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("ok"));

        Outcome outcome = drain(router(primary, fallback).stream(streamingRequest()));

        assertThat(outcome.contents()).containsExactly("ok");
    }

    @Test
    void retriesThePrimaryBeforeFailingOverWhenItRecovers() {
        var calls = new AtomicInteger();
        var primary = new StreamingAdapter("mock-primary",
                () -> calls.getAndIncrement() == 0 ? failing(429, true) : serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(primary, fallback).stream(streamingRequest()));

        assertThat(outcome.contents()).containsExactly("primary");
        assertThat(fallback.streamInvocations.get()).isZero();
    }

    @Test
    void returnsATerminalErrorImmediatelyWithoutRetryingOrFailingOver() {
        var primary = new StreamingAdapter("mock-primary", () -> failing(400, false));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(primary, fallback).stream(streamingRequest()));

        assertThat(outcome.error()).isInstanceOfSatisfying(ProviderStreamException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(400));
        assertThat(primary.streamInvocations.get()).isEqualTo(1);
        assertThat(fallback.streamInvocations.get()).isZero();
    }

    @Test
    void staysOnTheProviderOnceAChunkHasBeenEmitted() {
        var primary = new StreamingAdapter("mock-primary", () -> serving("Hel").concatWith(failing(503, true)));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(primary, fallback).stream(streamingRequest()));

        assertThat(outcome.contents()).containsExactly("Hel");
        assertThat(outcome.error()).isInstanceOfSatisfying(ProviderStreamException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
        assertThat(primary.streamInvocations.get()).isEqualTo(1);
        assertThat(fallback.streamInvocations.get()).isZero();
    }

    @Test
    void skipsAMemberWhoseBreakerIsOpen() {
        var breakers = CircuitBreakerRegistry.ofDefaults();
        breakers.circuitBreaker("mock-primary:mock").transitionToOpenState();
        var primary = new StreamingAdapter("mock-primary", () -> serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(primary, fallback, breakers).stream(streamingRequest()));

        assertThat(outcome.contents()).containsExactly("fallback");
        assertThat(primary.streamInvocations.get()).isZero();
    }

    @Test
    void skipsAMemberWhoseBulkheadIsFull() {
        var bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        bulkheads.bulkhead("mock-primary").tryAcquirePermission();
        var primary = new StreamingAdapter("mock-primary", () -> serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(Map.of("mock-primary", primary, "mock-fallback", fallback),
                CircuitBreakerRegistry.ofDefaults(), bulkheads).stream(streamingRequest()));

        assertThat(outcome.contents()).containsExactly("fallback");
        assertThat(primary.streamInvocations.get()).isZero();
    }

    @Test
    void releasesTheBulkheadOnceTheStreamEnds() {
        var bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        var primary = new StreamingAdapter("mock-primary", () -> serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));
        var router = router(Map.of("mock-primary", primary, "mock-fallback", fallback), CircuitBreakerRegistry.ofDefaults(), bulkheads);

        drain(router.stream(streamingRequest()));
        Outcome second = drain(router.stream(streamingRequest()));

        assertThat(second.contents()).containsExactly("primary");
    }

    @Test
    void givesBackTheBulkheadAndBreakerPermissionWhenTheClientCancelsBeforeTheFirstChunk() {
        var bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        var breakers = CircuitBreakerRegistry.of(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        breakers.circuitBreaker("mock-primary:mock").transitionToOpenState();
        breakers.circuitBreaker("mock-primary:mock").transitionToHalfOpenState();
        var calls = new AtomicInteger();
        var primary = new StreamingAdapter("mock-primary",
                () -> calls.getAndIncrement() == 0 ? Flux.never() : serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));
        var router = router(Map.of("mock-primary", primary, "mock-fallback", fallback), breakers, bulkheads);

        JdkFlowAdapter.flowPublisherToFlux(router.stream(streamingRequest())).subscribe().dispose();
        Outcome second = drain(router.stream(streamingRequest()));

        assertThat(second.contents()).containsExactly("primary");
    }

    @Test
    void surfacesTheAllProvidersUnavailableErrorWhenEveryMemberFails() {
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> failing(500, true));

        var publisher = router(primary, fallback).stream(streamingRequest());
        Outcome outcome = drain(publisher);

        assertThat(outcome.error()).isInstanceOfSatisfying(ProviderStreamException.class, failure -> {
            assertThat(failure.httpStatus()).isEqualTo(502);
            assertThat(failure.errorCode()).isEqualTo("no_healthy_provider");
            assertThat(failure.getMessage()).contains("mock-primary", "mock-fallback");
        });
        assertThat(attributionOf(publisher)).isEqualTo(new StreamAttribution(null, 2, List.of("mock-primary", "mock-fallback")));
    }

    @Test
    void answersAnUnknownRouteWithATerminalError() {
        var primary = new StreamingAdapter("mock-primary", () -> serving("primary"));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        Outcome outcome = drain(router(primary, fallback).stream(
                new ChatCompletionRequest("no-such-model", List.of(new ChatMessage("user", "hi")), true)));

        assertThat(outcome.error()).isInstanceOfSatisfying(ProviderStreamException.class, failure -> {
            assertThat(failure.httpStatus()).isEqualTo(400);
            assertThat(failure.errorCode()).isEqualTo("unknown_route");
        });
    }

    @Test
    void attributesAFailedOverStreamToTheProviderThatServedIt() {
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("ok"));

        var publisher = router(primary, fallback).stream(streamingRequest());
        drain(publisher);

        assertThat(attributionOf(publisher)).isEqualTo(new StreamAttribution("mock-fallback", 1, List.of("mock-primary", "mock-fallback")));
    }

    @Test
    void attributionIsKnownByTheTimeTheFirstChunkArrives() {
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("a", "b"));
        var publisher = router(primary, fallback).stream(streamingRequest());

        List<Optional<StreamAttribution>> seenAtFirstChunk = new CopyOnWriteArrayList<>();
        JdkFlowAdapter.flowPublisherToFlux(publisher)
                .take(1)
                .doOnNext(c -> seenAtFirstChunk.add(((AttributedStream) publisher).attribution()))
                .blockLast(Duration.ofSeconds(5));

        assertThat(seenAtFirstChunk).singleElement()
                .isEqualTo(Optional.of(new StreamAttribution("mock-fallback", 1, List.of("mock-primary", "mock-fallback"))));
    }

    @Test
    void recordsABreakerFailureForEachRetryableFailureBeforeTheFirstChunk() {
        var breakers = CircuitBreakerRegistry.ofDefaults();
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("ok"));

        drain(router(primary, fallback, breakers).stream(streamingRequest()));

        CircuitBreaker.Metrics primaryMetrics = breakers.circuitBreaker("mock-primary:mock").getMetrics();
        CircuitBreaker.Metrics fallbackMetrics = breakers.circuitBreaker("mock-fallback:mock").getMetrics();
        assertThat(primaryMetrics.getNumberOfFailedCalls()).isEqualTo(MAX_RETRIES_PER_PROVIDER + 1);
        assertThat(fallbackMetrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(fallbackMetrics.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void aFailureAfterTheFirstChunkDoesNotCountAgainstTheBreaker() {
        var breakers = CircuitBreakerRegistry.ofDefaults();
        var primary = new StreamingAdapter("mock-primary", () -> serving("Hel").concatWith(failing(503, true)));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("fallback"));

        drain(router(primary, fallback, breakers).stream(streamingRequest()));

        CircuitBreaker.Metrics metrics = breakers.circuitBreaker("mock-primary:mock").getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void theBreakerOpensAfterRepeatedStreamingFailuresAndThePrimaryIsThenSkipped() {
        var breakers = CircuitBreakerRegistry.of(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .build());
        var primary = new StreamingAdapter("mock-primary", () -> failing(503, true));
        var fallback = new StreamingAdapter("mock-fallback", () -> serving("ok"));
        var router = router(primary, fallback, breakers);

        drain(router.stream(streamingRequest()));
        drain(router.stream(streamingRequest()));
        int invocationsWhenOpen = primary.streamInvocations.get();
        Outcome outcome = drain(router.stream(streamingRequest()));

        assertThat(breakers.circuitBreaker("mock-primary:mock").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(outcome.contents()).containsExactly("ok");
        assertThat(primary.streamInvocations.get()).isEqualTo(invocationsWhenOpen);
    }
}
