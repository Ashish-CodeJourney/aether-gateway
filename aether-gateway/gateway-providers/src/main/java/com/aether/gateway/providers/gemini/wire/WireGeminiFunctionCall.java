package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** {@code id} is optional on the wire and usually absent from Gemini API responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiFunctionCall(String id, String name, JsonNode args) {
}
