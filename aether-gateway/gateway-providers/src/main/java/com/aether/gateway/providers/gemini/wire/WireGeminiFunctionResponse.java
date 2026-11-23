package com.aether.gateway.providers.gemini.wire;

import tools.jackson.databind.JsonNode;

/** {@code response} must be a JSON object; Gemini matches it to its call by {@code name}. */
public record WireGeminiFunctionResponse(String name, JsonNode response) {
}
