package com.aether.gateway.core.domain;

import java.util.Optional;

/** F7.2: parses the {@code X-Aether-Prompt} header's {@code name@alias} or {@code name@version} shape (PRD 13.1). */
public record PromptReference(String promptName, Optional<String> alias, Optional<Integer> version) {

    public static PromptReference parse(String headerValue) {
        int atIndex = headerValue.indexOf('@');
        if (atIndex < 0) {
            throw new IllegalArgumentException("X-Aether-Prompt must be in the form name@alias or name@version: " + headerValue);
        }
        String promptName = headerValue.substring(0, atIndex);
        String suffix = headerValue.substring(atIndex + 1);
        if (promptName.isBlank()) {
            throw new IllegalArgumentException("X-Aether-Prompt is missing a prompt name: " + headerValue);
        }
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("X-Aether-Prompt is missing an alias or version: " + headerValue);
        }

        try {
            return new PromptReference(promptName, Optional.empty(), Optional.of(Integer.parseInt(suffix)));
        } catch (NumberFormatException e) {
            return new PromptReference(promptName, Optional.of(suffix), Optional.empty());
        }
    }
}
