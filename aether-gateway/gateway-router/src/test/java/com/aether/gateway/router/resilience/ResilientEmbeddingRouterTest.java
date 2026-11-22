package com.aether.gateway.router.resilience;

import com.aether.gateway.core.domain.ChainMember;
import com.aether.gateway.core.domain.ChatCompletionRequest;
import com.aether.gateway.core.domain.Embedding;
import com.aether.gateway.core.domain.EmbeddingOutcome;
import com.aether.gateway.core.domain.EmbeddingRequest;
import com.aether.gateway.core.domain.EmbeddingResponse;
import com.aether.gateway.core.domain.ProviderResponse;
import com.aether.gateway.core.domain.RetryBackoff;
import com.aether.gateway.core.domain.RouteConfig;
import com.aether.gateway.core.port.ProviderAdapter;
import com.aether.gateway.router.routing.RoutingSource;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientEmbeddingRouterTest {

    private static EmbeddingOutcome success(String from) {
        return new EmbeddingOutcome.Success(new EmbeddingResponse(
                "model-" + from, List.of(new Embedding(0, List.of(0.1f, 0.2f))), 3));
    }

    private static EmbeddingOutcome failure(int status) {
        return new EmbeddingOutcome.Failure(new ProviderResponse.ProviderError(
                "provider_error", "status " + status, status, status == 429 || status == 503 || status == 504));
    }

    /** A provider that only speaks chat, like Anthropic: relies on the port's default embed(). */
    private static class ChatOnlyAdapter implements ProviderAdapter {
        private final String name;

        ChatOnlyAdapter(String name) {
            this.name = name;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public ProviderResponse invoke(ChatCompletionRequest request) {
            throw new UnsupportedOperationException("not under test");
        }

        @Override
        public Flow.Publisher<ProviderResponse.StreamChunk> invokeStreaming(ChatCompletionRequest request) {
            throw new UnsupportedOperationException("not under test");
        }
    }

    private static class FakeEmbeddingAdapter extends ChatOnlyAdapter {
        private final Supplier<EmbeddingOutcome> outcome;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<String> lastReceivedModel = new AtomicReference<>();

        FakeEmbeddingAdapter(String name, Supplier<EmbeddingOutcome> outcome) {
            super(name);
            this.outcome = outcome;
        }

        @Override
        public EmbeddingOutcome embed(EmbeddingRequest request) {
            invocationCount.incrementAndGet();
            lastReceivedModel.set(request.model());
            return outcome.get();
        }
    }

    private static RouteConfig twoMemberRoute() {
        return new RouteConfig("embed", List.of(
                new ChainMember("primary", "upstream-a", null),
                new ChainMember("fallback", "upstream-b", null)));
    }

    private static EmbeddingRequest request() {
        return new EmbeddingRequest("embed", List.of("hello"), null);
    }

    private ResilientEmbeddingRouter router(Map<String, ProviderAdapter> adapters, RouteConfig route) {
        RoutingSource source = new RoutingSource() {
            @Override
            public Optional<RouteConfig> routeFor(String alias) {
                return alias.equals(route.alias()) ? Optional.of(route) : Optional.empty();
            }

            @Override
            public Optional<ProviderAdapter> adapterFor(String providerName) {
                return Optional.ofNullable(adapters.get(providerName));
            }

            @Override
            public Map<String, RouteConfig> allRoutes() {
                return Map.of(route.alias(), route);
            }
        };
        return new ResilientEmbeddingRouter(
                source, CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(),
                new RetryBackoff(1, 5), 2, Duration.ofSeconds(5), null);
    }

    @Test
    void servesFromTheFirstChainMemberAndAttributesTheResult() {
        var primary = new FakeEmbeddingAdapter("primary", () -> success("primary"));
        var fallback = new FakeEmbeddingAdapter("fallback", () -> success("fallback"));

        var outcome = router(Map.of("primary", primary, "fallback", fallback), twoMemberRoute()).embed(request());

        var result = (EmbeddingOutcome.Success) outcome;
        assertThat(result.response().model()).isEqualTo("model-primary");
        assertThat(result.servedByProvider()).isEqualTo("primary");
        assertThat(result.servedModel()).isEqualTo("upstream-a");
        assertThat(result.attemptCount()).isEqualTo(1);
        assertThat(result.failoverChain()).containsExactly("primary");
        assertThat(fallback.invocationCount.get()).isZero();
    }

    @Test
    void sendsTheChainMembersUpstreamModelNotTheAlias() {
        var primary = new FakeEmbeddingAdapter("primary", () -> success("primary"));

        router(Map.of("primary", primary), twoMemberRoute()).embed(request());

        assertThat(primary.lastReceivedModel.get()).isEqualTo("upstream-a");
    }

    @Test
    void retriesARetryableFailureThenFailsOverToTheNextMember() {
        var primary = new FakeEmbeddingAdapter("primary", () -> failure(503));
        var fallback = new FakeEmbeddingAdapter("fallback", () -> success("fallback"));

        var outcome = router(Map.of("primary", primary, "fallback", fallback), twoMemberRoute()).embed(request());

        var result = (EmbeddingOutcome.Success) outcome;
        assertThat(result.servedByProvider()).isEqualTo("fallback");
        assertThat(result.servedModel()).isEqualTo("upstream-b");
        assertThat(result.failoverChain()).containsExactly("primary", "fallback");
        assertThat(primary.invocationCount.get()).isEqualTo(3);
        assertThat(result.attemptCount()).as("every provider call across the chain").isEqualTo(4);
    }

    @Test
    void returnsATerminalFailureImmediatelyWithoutFailover() {
        var primary = new FakeEmbeddingAdapter("primary", () -> failure(400));
        var fallback = new FakeEmbeddingAdapter("fallback", () -> success("fallback"));

        var outcome = router(Map.of("primary", primary, "fallback", fallback), twoMemberRoute()).embed(request());

        var result = (EmbeddingOutcome.Failure) outcome;
        assertThat(result.error().httpStatus()).isEqualTo(400);
        assertThat(result.error().attemptCount()).isEqualTo(1);
        assertThat(primary.invocationCount.get()).isEqualTo(1);
        assertThat(fallback.invocationCount.get()).isZero();
    }

    @Test
    void skipsAProviderThatDoesNotSupportEmbeddingsAndFailsOverToOneThatDoes() {
        var chatOnly = new ChatOnlyAdapter("primary");
        var fallback = new FakeEmbeddingAdapter("fallback", () -> success("fallback"));

        var outcome = router(Map.of("primary", chatOnly, "fallback", fallback), twoMemberRoute()).embed(request());

        var result = (EmbeddingOutcome.Success) outcome;
        assertThat(result.servedByProvider()).isEqualTo("fallback");
        assertThat(result.failoverChain()).containsExactly("primary", "fallback");
    }

    @Test
    void reportsAClearClientErrorWhenNoMemberOfTheRouteSupportsEmbeddings() {
        var route = new RouteConfig("embed", List.of(new ChainMember("anthropic-main", "claude", null)));

        var outcome = router(Map.of("anthropic-main", new ChatOnlyAdapter("anthropic-main")), route).embed(request());

        var error = ((EmbeddingOutcome.Failure) outcome).error();
        assertThat(error.errorCode()).isEqualTo(EmbeddingOutcome.EMBEDDINGS_NOT_SUPPORTED);
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.message()).contains("embed").contains("anthropic-main");
        assertThat(error.failoverChain()).containsExactly("anthropic-main");
    }

    @Test
    void reportsNoHealthyProviderWhenEveryMemberKeepsFailingRetryably() {
        var primary = new FakeEmbeddingAdapter("primary", () -> failure(503));
        var fallback = new FakeEmbeddingAdapter("fallback", () -> failure(429));

        var outcome = router(Map.of("primary", primary, "fallback", fallback), twoMemberRoute()).embed(request());

        var error = ((EmbeddingOutcome.Failure) outcome).error();
        assertThat(error.errorCode()).isEqualTo("no_healthy_provider");
        assertThat(error.httpStatus()).isEqualTo(502);
        assertThat(error.failoverChain()).containsExactly("primary", "fallback");
    }

    @Test
    void rejectsAnUnknownRouteAlias() {
        var outcome = router(Map.of(), twoMemberRoute()).embed(new EmbeddingRequest("nope", List.of("hello"), null));

        var error = ((EmbeddingOutcome.Failure) outcome).error();
        assertThat(error.errorCode()).isEqualTo("unknown_route");
        assertThat(error.httpStatus()).isEqualTo(400);
    }
}
