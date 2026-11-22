package com.aether.gateway.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** F4.1: exact-match cache keyed by SHA-256 of (normalised messages + model + params). */
public final class CacheKey {

    private CacheKey() {
    }

    public static String exactHash(ChatCompletionRequest request) {
        String canonical = canonicalize(request);
        return sha256Hex(canonical);
    }

    private static String canonicalize(ChatCompletionRequest request) {
        String messagesPart = request.messages().stream()
                .map(CacheKey::canonicalizeMessage)
                .collect(Collectors.joining("|"));
        String toolsPart = String.join(",", request.tools());
        return "model=" + request.model()
                + ";temperature=" + request.temperature()
                + ";tools=" + toolsPart
                + ";toolChoice=" + request.toolChoice()
                + ";messages=" + messagesPart;
    }

    private static String canonicalizeMessage(ChatMessage message) {
        String base = message.role() + ":" + normalizeWhitespace(message.content());
        if (!message.hasToolContent()) {
            return base;
        }
        String calls = message.toolCalls().stream()
                .map(call -> call.id() + "/" + call.functionName() + "/" + call.argumentsJson())
                .collect(Collectors.joining(","));
        return base + "[toolCallId=" + message.toolCallId() + ";toolCalls=" + calls + "]";
    }

    private static String normalizeWhitespace(String content) {
        return content.strip().replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
