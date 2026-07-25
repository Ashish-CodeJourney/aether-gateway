package com.aether.gateway.proxy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * F8.5: admin authentication is a genuinely separate mechanism from
 * gateway API keys, not a reuse of the same header/lookup - a valid
 * {@code Authorization: Bearer <gateway key>} carries zero weight here,
 * only {@code X-Aether-Admin-Key} does. If no admin key is configured
 * (blank {@code aether.admin.api-key}), every {@code /admin/**} request
 * is rejected - fail closed, per the phase doc's explicit warning
 * against admin auth as a retrofitted afterthought.
 */
@Component
public class AdminAuthFilter implements WebFilter {

    private static final String ADMIN_KEY_HEADER = "X-Aether-Admin-Key";

    private final String configuredAdminKey;

    public AdminAuthFilter(@Value("${aether.admin.api-key:}") String configuredAdminKey) {
        this.configuredAdminKey = configuredAdminKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/admin/")) {
            return chain.filter(exchange);
        }
        // CORS preflight (PRD 9.4's console calling cross-origin) carries
        // no custom headers by browser design - it must reach
        // CorsWebFilter unauthenticated, or the browser never even
        // attempts the real, authenticated request that follows it.
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String presented = exchange.getRequest().getHeaders().getFirst(ADMIN_KEY_HEADER);
        if (configuredAdminKey.isBlank() || presented == null || !constantTimeEquals(presented, configuredAdminKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
