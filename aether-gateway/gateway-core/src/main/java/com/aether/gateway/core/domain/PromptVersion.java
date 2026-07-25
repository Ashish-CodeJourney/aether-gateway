package com.aether.gateway.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** F7.1: one immutable version of a named prompt template. */
public record PromptVersion(
        String promptId,
        String promptName,
        int version,
        List<ChatMessage> template,
        Set<String> variables,
        String modelDefaultsJson,
        Instant createdAt) {
}
