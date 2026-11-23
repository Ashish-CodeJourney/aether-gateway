package com.aether.gateway.quota;

import java.security.SecureRandom;
import java.util.Base64;

/** F9.1: issues high-entropy raw API keys, shared by every API key store so keys look the same whichever store issued them. */
public final class ApiKeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PREFIX_LENGTH = 12;

    private ApiKeyGenerator() {
    }

    public static String generateRawKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "aeth_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The non-secret leading characters shown to operators so they can tell keys apart. */
    public static String prefixOf(String rawKey) {
        return rawKey.substring(0, Math.min(PREFIX_LENGTH, rawKey.length()));
    }
}
