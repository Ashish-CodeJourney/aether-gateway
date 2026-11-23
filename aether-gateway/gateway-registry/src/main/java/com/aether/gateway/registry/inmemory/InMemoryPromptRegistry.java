package com.aether.gateway.registry.inmemory;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptNotFoundException;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.port.PromptRegistryPort;

import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * ADR-010 lite mode: F7.1-F7.4 prompt registry in process memory, lost
 * on restart. Each prompt is one immutable value replaced atomically on
 * every change, so a version number is never handed out twice and an
 * alias re-point is visible to the very next lookup.
 */
public class InMemoryPromptRegistry implements PromptRegistryPort {

    private record Prompt(String id, List<PromptVersion> versions, Map<String, Integer> aliases) {

        Prompt withVersion(PromptVersion version) {
            return new Prompt(id, Stream.concat(versions.stream(), Stream.of(version)).toList(), aliases);
        }

        Prompt withAlias(String alias, int version) {
            Map<String, Integer> updated = new HashMap<>(aliases);
            updated.put(alias, version);
            return new Prompt(id, versions, Map.copyOf(updated));
        }

        Optional<PromptVersion> version(int number) {
            return versions.stream().filter(v -> v.version() == number).findFirst();
        }
    }

    private final InstantSource clock;
    private final Map<String, Prompt> promptsByName = new ConcurrentHashMap<>();

    public InMemoryPromptRegistry(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String createPrompt(String promptName) {
        Prompt created = new Prompt(UUID.randomUUID().toString(), List.of(), Map.of());
        if (promptsByName.putIfAbsent(promptName, created) != null) {
            throw new IllegalArgumentException("A prompt named '" + promptName + "' already exists");
        }
        return created.id();
    }

    @Override
    public PromptVersion createVersion(String promptName, List<ChatMessage> template, Set<String> variables, String modelDefaultsJson) {
        AtomicReference<PromptVersion> created = new AtomicReference<>();
        Prompt updated = promptsByName.computeIfPresent(promptName, (name, prompt) -> {
            PromptVersion version = new PromptVersion(
                    prompt.id(), name, prompt.versions().size() + 1,
                    List.copyOf(template), Set.copyOf(variables), modelDefaultsJson, clock.instant());
            created.set(version);
            return prompt.withVersion(version);
        });
        if (updated == null) {
            throw new PromptNotFoundException(promptName);
        }
        return created.get();
    }

    @Override
    public void setAlias(String promptName, String alias, int version) {
        if (promptsByName.computeIfPresent(promptName, (name, prompt) -> prompt.withAlias(alias, version)) == null) {
            throw new PromptNotFoundException(promptName);
        }
    }

    @Override
    public Optional<PromptVersion> findByNameAndVersion(String promptName, int version) {
        return Optional.ofNullable(promptsByName.get(promptName)).flatMap(prompt -> prompt.version(version));
    }

    @Override
    public Optional<PromptVersion> findByNameAndAlias(String promptName, String alias) {
        return Optional.ofNullable(promptsByName.get(promptName))
                .flatMap(prompt -> Optional.ofNullable(prompt.aliases().get(alias)).flatMap(prompt::version));
    }
}
