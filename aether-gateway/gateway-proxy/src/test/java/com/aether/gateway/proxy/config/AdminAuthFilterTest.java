package com.aether.gateway.proxy.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** F8.5: admin auth must be a real, separate boundary from gateway API keys, and never block the CORS preflight it depends on. */
class AdminAuthFilterTest {

    private static final String CONFIGURED_KEY = "correct-admin-key";

    @Test
    void rejectsAnAdminRequestWithNoKey() {
        AdminAuthFilter filter = new AdminAuthFilter(CONFIGURED_KEY);
        ServerWebExchange exchange = exchangeFor(HttpMethod.GET, "/admin/routes", null);
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnAdminRequestWithTheWrongKey() {
        AdminAuthFilter filter = new AdminAuthFilter(CONFIGURED_KEY);
        ServerWebExchange exchange = exchangeFor(HttpMethod.GET, "/admin/routes", "wrong-key");
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptsAnAdminRequestWithTheCorrectKey() {
        AdminAuthFilter filter = new AdminAuthFilter(CONFIGURED_KEY);
        ServerWebExchange exchange = exchangeFor(HttpMethod.GET, "/admin/routes", CONFIGURED_KEY);
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isTrue();
    }

    @Test
    void rejectsEveryAdminRequestWhenNoKeyIsConfigured() {
        AdminAuthFilter filter = new AdminAuthFilter("");
        ServerWebExchange exchange = exchangeFor(HttpMethod.GET, "/admin/routes", "anything");
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void doesNotApplyToNonAdminPaths() {
        AdminAuthFilter filter = new AdminAuthFilter(CONFIGURED_KEY);
        ServerWebExchange exchange = exchangeFor(HttpMethod.POST, "/v1/chat/completions", null);
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isTrue();
    }

    /** The CORS preflight for the console (PRD 9.4) carries no custom headers by browser design - it must reach CorsWebFilter unauthenticated. */
    @Test
    void letsAnOptionsPreflightThroughWithNoKey() {
        AdminAuthFilter filter = new AdminAuthFilter(CONFIGURED_KEY);
        ServerWebExchange exchange = exchangeFor(HttpMethod.OPTIONS, "/admin/routes", null);
        AtomicBoolean chainReached = new AtomicBoolean(false);

        filter.filter(exchange, recordingChain(chainReached)).block();

        assertThat(chainReached).isTrue();
    }

    private static ServerWebExchange exchangeFor(HttpMethod method, String path, String adminKey) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, path);
        if (adminKey != null) {
            builder.header("X-Aether-Admin-Key", adminKey);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static WebFilterChain recordingChain(AtomicBoolean chainReached) {
        return exchange -> {
            chainReached.set(true);
            return Mono.empty();
        };
    }
}
