package com.aether.gateway.core.domain;

public record ChatMessage(String role, String content) {
    public ChatMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
    }
}
