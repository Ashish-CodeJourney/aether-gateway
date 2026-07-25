package com.aether.gateway.router.routing;

/**
 * {@code type} selects which {@link com.aether.gateway.core.port.ProviderAdapter}
 * implementation is wired up (Phase 12/M9: "mock", "ollama", "groq",
 * "gemini", "openai-compatible") and defaults to "mock" for
 * backward-compatible routing.yaml files that predate real providers.
 * {@code apiKeyEnvVar} carries the *name* of an environment variable to
 * read the real credential from, never the credential itself (F9.2) -
 * this parser stays a pure function over YAML text with no environment
 * access; resolving the named variable to an actual key happens where
 * adapters are wired up (a Spring bean factory method), not here.
 */
public record ProviderConfig(String name, String baseUrl, String type, String apiKeyEnvVar) {

    public ProviderConfig(String name, String baseUrl) {
        this(name, baseUrl, "mock", null);
    }
}
