package com.aether.gateway.core.port;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * F7.1-F7.4: named prompt templates with immutable versions and mutable
 * aliases (e.g. {@code production}/{@code staging}). Rollback (F7.4) is
 * modelled as re-pointing an alias to a prior version - there is no
 * separate "rollback" operation, {@link #setAlias} is both the promote
 * and the rollback path, matching the phase doc's exit criterion
 * ("no gateway restart").
 */
public interface PromptRegistryPort {

    Optional<PromptVersion> findByNameAndVersion(String promptName, int version);

    Optional<PromptVersion> findByNameAndAlias(String promptName, String alias);

    String createPrompt(String promptName);

    PromptVersion createVersion(String promptName, List<ChatMessage> template, Set<String> variables, String modelDefaultsJson);

    void setAlias(String promptName, String alias, int version);
}
