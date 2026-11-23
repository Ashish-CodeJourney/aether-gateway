package com.aether.gateway.registry;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.port.PromptRegistryPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F7.1-F7.4: what every prompt registry promises, whichever mode the
 * gateway runs in (ADR-010). Characterised against the Postgres
 * registry in integrationTest and run unchanged against the in-memory
 * one. Every test uses its own prompt name, so a shared store is fine.
 */
public abstract class PromptRegistryContract {

    protected abstract PromptRegistryPort registry();

    private static String freshName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void createsAPromptAndVersionAndFindsItByVersionNumber() {
        String promptName = freshName("greeting");
        String promptId = registry().createPrompt(promptName);

        List<ChatMessage> template = List.of(
                new ChatMessage("system", "You are terse."),
                new ChatMessage("user", "Hello {{name}}."));
        PromptVersion created = registry().createVersion(promptName, template, Set.of("name"), null);

        assertThat(created.promptId()).isEqualTo(promptId);
        assertThat(created.promptName()).isEqualTo(promptName);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.createdAt()).isNotNull();

        PromptVersion found = registry().findByNameAndVersion(promptName, 1).orElseThrow();
        assertThat(found.promptId()).isEqualTo(promptId);
        assertThat(found.template()).containsExactlyElementsOf(template);
        assertThat(found.variables()).containsExactly("name");
        assertThat(found.modelDefaultsJson()).isNull();
    }

    @Test
    void modelDefaultsRoundTrip() {
        String promptName = freshName("defaults");
        registry().createPrompt(promptName);

        registry().createVersion(promptName, List.of(new ChatMessage("user", "hi")), Set.of(), "{\"temperature\": 0.2}");

        assertThat(registry().findByNameAndVersion(promptName, 1).orElseThrow().modelDefaultsJson())
                .isEqualTo("{\"temperature\": 0.2}");
    }

    @Test
    void versionNumbersIncrementPerPrompt() {
        String promptName = freshName("counter");
        String otherName = freshName("other");
        registry().createPrompt(promptName);
        registry().createPrompt(otherName);

        PromptVersion v1 = registry().createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);
        PromptVersion v2 = registry().createVersion(promptName, List.of(new ChatMessage("user", "v2")), Set.of(), null);
        PromptVersion otherV1 = registry().createVersion(otherName, List.of(new ChatMessage("user", "o1")), Set.of(), null);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(otherV1.version()).isEqualTo(1);
    }

    @Test
    void earlierVersionsStayUnchanged() {
        String promptName = freshName("immutable");
        registry().createPrompt(promptName);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "first")), Set.of(), null);

        registry().createVersion(promptName, List.of(new ChatMessage("user", "second")), Set.of(), null);

        assertThat(registry().findByNameAndVersion(promptName, 1).orElseThrow().template())
                .containsExactly(new ChatMessage("user", "first"));
    }

    @Test
    void findByNameAndVersionIsEmptyForAnUnknownVersionOrPrompt() {
        String promptName = freshName("lonely");
        registry().createPrompt(promptName);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "hi")), Set.of(), null);

        assertThat(registry().findByNameAndVersion(promptName, 99)).isEmpty();
        assertThat(registry().findByNameAndVersion(freshName("missing"), 1)).isEmpty();
    }

    @Test
    void setAliasThenFindByAliasResolvesToThePointedVersion() {
        String promptName = freshName("aliased");
        registry().createPrompt(promptName);
        PromptVersion v1 = registry().createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);

        registry().setAlias(promptName, "production", v1.version());

        Optional<PromptVersion> resolved = registry().findByNameAndAlias(promptName, "production");
        assertThat(resolved).get().extracting(PromptVersion::version).isEqualTo(1);
    }

    @Test
    void anUnsetAliasResolvesToNothing() {
        String promptName = freshName("unaliased");
        registry().createPrompt(promptName);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);

        assertThat(registry().findByNameAndAlias(promptName, "production")).isEmpty();
        assertThat(registry().findByNameAndAlias(freshName("missing"), "production")).isEmpty();
    }

    @Test
    void anAliasPointingAtAVersionThatDoesNotExistResolvesToNothing() {
        String promptName = freshName("dangling");
        registry().createPrompt(promptName);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);

        registry().setAlias(promptName, "staging", 7);

        assertThat(registry().findByNameAndAlias(promptName, "staging")).isEmpty();
    }

    @Test
    void aliasesAreIndependentOfEachOther() {
        String promptName = freshName("two-aliases");
        registry().createPrompt(promptName);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "v1")), Set.of(), null);
        registry().createVersion(promptName, List.of(new ChatMessage("user", "v2")), Set.of(), null);

        registry().setAlias(promptName, "production", 1);
        registry().setAlias(promptName, "staging", 2);

        assertThat(registry().findByNameAndAlias(promptName, "production")).get().extracting(PromptVersion::version).isEqualTo(1);
        assertThat(registry().findByNameAndAlias(promptName, "staging")).get().extracting(PromptVersion::version).isEqualTo(2);
    }

    /** F7.4's exit criterion at the port level: rollback is re-pointing, takes effect immediately, no restart. */
    @Test
    void repointingAnAliasTakesEffectImmediatelyOnTheNextLookup() {
        String promptName = freshName("rollback");
        registry().createPrompt(promptName);
        PromptVersion v1 = registry().createVersion(promptName, List.of(new ChatMessage("user", "version one")), Set.of(), null);
        PromptVersion v2 = registry().createVersion(promptName, List.of(new ChatMessage("user", "version two")), Set.of(), null);

        registry().setAlias(promptName, "production", v2.version());
        assertThat(registry().findByNameAndAlias(promptName, "production")).get()
                .extracting(PromptVersion::version).isEqualTo(2);

        registry().setAlias(promptName, "production", v1.version());
        assertThat(registry().findByNameAndAlias(promptName, "production")).get()
                .extracting(PromptVersion::version).isEqualTo(1);
    }

    @Test
    void creatingAPromptWhoseNameIsTakenFails() {
        String promptName = freshName("taken");
        registry().createPrompt(promptName);

        assertThatThrownBy(() -> registry().createPrompt(promptName)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void versioningOrAliasingAnUnknownPromptFails() {
        String promptName = freshName("unknown");

        assertThatThrownBy(() -> registry().createVersion(promptName, List.of(new ChatMessage("user", "hi")), Set.of(), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> registry().setAlias(promptName, "production", 1))
                .isInstanceOf(RuntimeException.class);
    }
}
