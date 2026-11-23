package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** {@code parameters} is Gemini's OpenAPI-subset Schema object, omitted for a function that takes no arguments. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiFunctionDeclaration(String name, String description, JsonNode parameters) {
}
