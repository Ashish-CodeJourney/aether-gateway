package com.aether.gateway.proxy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-010: a lite gateway's admin API must be usable out of the box, but never open. */
class LiteAdminKeyEnvironmentPostProcessorTest {

    private final LiteAdminKeyEnvironmentPostProcessor processor = new LiteAdminKeyEnvironmentPostProcessor();

    private MockEnvironment processed(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void liteModeWithNoAdminKeyGetsAGeneratedOne() {
        var environment = processed(new MockEnvironment().withProperty("aether.mode", "lite"));

        assertThat(environment.getProperty("aether.admin.api-key")).startsWith("aeth_admin_").hasSizeGreaterThan(30);
        assertThat(environment.getProperty(LiteAdminKeyEnvironmentPostProcessor.GENERATED_MARKER, Boolean.class)).isTrue();
    }

    @Test
    void liteModeWithABlankAdminKeyGetsAGeneratedOne() {
        var environment = processed(new MockEnvironment()
                .withProperty("aether.mode", "lite")
                .withProperty("aether.admin.api-key", " "));

        assertThat(environment.getProperty("aether.admin.api-key")).startsWith("aeth_admin_");
    }

    @Test
    void everyGeneratedKeyIsDifferent() {
        var first = processed(new MockEnvironment().withProperty("aether.mode", "lite"));
        var second = processed(new MockEnvironment().withProperty("aether.mode", "lite"));

        assertThat(first.getProperty("aether.admin.api-key")).isNotEqualTo(second.getProperty("aether.admin.api-key"));
    }

    @Test
    void aConfiguredAdminKeyIsKept() {
        var environment = processed(new MockEnvironment()
                .withProperty("aether.mode", "lite")
                .withProperty("aether.admin.api-key", "operator-chosen"));

        assertThat(environment.getProperty("aether.admin.api-key")).isEqualTo("operator-chosen");
        assertThat(environment.containsProperty(LiteAdminKeyEnvironmentPostProcessor.GENERATED_MARKER)).isFalse();
    }

    @Test
    void fullModeStaysFailClosedWithNoAdminKey() {
        var environment = processed(new MockEnvironment());

        assertThat(environment.containsProperty("aether.admin.api-key")).isFalse();
    }
}
