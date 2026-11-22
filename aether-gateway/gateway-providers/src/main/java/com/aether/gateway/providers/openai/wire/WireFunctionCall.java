package com.aether.gateway.providers.openai.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireFunctionCall(String name, String arguments) {
}
