package com.aether.gateway.providers.gemini.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** {@code mode} is AUTO, ANY or NONE; {@code allowedFunctionNames} is only valid with ANY. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireGeminiFunctionCallingConfig(String mode, List<String> allowedFunctionNames) {
}
