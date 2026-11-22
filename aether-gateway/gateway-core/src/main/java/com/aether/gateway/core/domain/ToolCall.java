package com.aether.gateway.core.domain;

/**
 * One function call an assistant asked the client to make. {@code argumentsJson}
 * is kept exactly as the provider produced it - an opaque JSON string, never
 * parsed here - so a call relayed through the gateway reaches the client
 * byte-for-byte, and gateway-core stays free of any JSON library.
 */
public record ToolCall(String id, String type, String functionName, String argumentsJson) {
    public ToolCall {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("tool call function name must not be blank");
        }
        type = type == null ? "function" : type;
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
