package com.aether.gateway.proxy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-010: exactly one infrastructure configuration is active, chosen by
 * {@code aether.mode}, and full mode is what you get without asking.
 * Beans are left lazy so nothing here needs a real database or Redis.
 */
class InfrastructureModeSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
            .withUserConfiguration(FullModeInfrastructureConfig.class, LiteModeInfrastructureConfig.class);

    @Test
    void fullModeIsTheDefault() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(FullModeInfrastructureConfig.class)
                .doesNotHaveBean(LiteModeInfrastructureConfig.class));
    }

    @Test
    void fullModeCanBeChosenExplicitly() {
        runner.withPropertyValues("aether.mode=full").run(context -> assertThat(context)
                .hasSingleBean(FullModeInfrastructureConfig.class)
                .doesNotHaveBean(LiteModeInfrastructureConfig.class));
    }

    @Test
    void liteModeReplacesFullMode() {
        runner.withPropertyValues("aether.mode=lite").run(context -> assertThat(context)
                .hasSingleBean(LiteModeInfrastructureConfig.class)
                .doesNotHaveBean(FullModeInfrastructureConfig.class));
    }
}
