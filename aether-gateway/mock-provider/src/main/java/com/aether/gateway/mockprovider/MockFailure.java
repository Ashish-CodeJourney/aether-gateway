package com.aether.gateway.mockprovider;

public record MockFailure(int httpStatus, String errorCode, boolean malformedBody, long artificialDelayMs) {

    public static MockFailure forMode(String mode) {
        return switch (mode) {
            case "429" -> new MockFailure(429, "rate_limited", false, 0);
            case "500" -> new MockFailure(500, "internal_error", false, 0);
            case "503" -> new MockFailure(503, "service_unavailable", false, 0);
            case "malformed" -> new MockFailure(200, "malformed_response", true, 0);
            case "timeout" -> new MockFailure(504, "timeout", false, 30_000);
            default -> throw new IllegalArgumentException("Unknown X-Mock-Fail mode: " + mode);
        };
    }
}
