package com.aether.gateway.core.domain;

public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
