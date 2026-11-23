package com.aether.gateway.registry.inmemory;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.port.PromptRegistryPort;
import com.aether.gateway.registry.PromptRegistryContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPromptRegistryTest extends PromptRegistryContract {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

    private final InMemoryPromptRegistry registry = new InMemoryPromptRegistry(InstantSource.fixed(NOW));

    @Override
    protected PromptRegistryPort registry() {
        return registry;
    }

    @Test
    void versionsAreStampedWithTheCurrentTime() {
        registry.createPrompt("stamped");

        assertThat(registry.createVersion("stamped", List.of(new ChatMessage("user", "hi")), Set.of(), null).createdAt())
                .isEqualTo(NOW);
    }

    @Test
    void storedTemplatesAndVariablesCannotBeChangedByTheCaller() {
        registry.createPrompt("defensive");
        var variables = new java.util.HashSet<>(Set.of("name"));

        registry.createVersion("defensive", List.of(new ChatMessage("user", "Hello {{name}}")), variables, null);
        variables.add("injected");

        assertThat(registry.findByNameAndVersion("defensive", 1).orElseThrow().variables()).containsExactly("name");
    }
}
