package com.aether.gateway.proxy.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * ADR-010: a lite gateway started with no {@code AETHER_ADMIN_API_KEY}
 * gets a freshly generated one, so its admin API is usable out of the
 * box without ever being open; {@link LiteModeStartupReport} logs it
 * once. Full mode is left alone and stays fail-closed with no key (F8.5).
 * Runs after config data is loaded, so the lite profile's
 * {@code aether.mode} is already visible.
 */
public class LiteAdminKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String GENERATED_MARKER = "aether.lite.admin-key-generated";
    private static final String ADMIN_KEY = "aether.admin.api-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"lite".equals(environment.getProperty("aether.mode"))) {
            return;
        }
        String configured = environment.getProperty(ADMIN_KEY, "");
        if (!configured.isBlank()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                "aetherLiteGeneratedAdminKey", Map.of(ADMIN_KEY, generateAdminKey(), GENERATED_MARKER, true)));
    }

    private static String generateAdminKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "aeth_admin_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
