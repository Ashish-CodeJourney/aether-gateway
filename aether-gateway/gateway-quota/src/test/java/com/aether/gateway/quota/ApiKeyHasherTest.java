package com.aether.gateway.quota;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void isDeterministicForTheSameKey() {
        assertThat(ApiKeyHasher.hash("aeth_test123")).isEqualTo(ApiKeyHasher.hash("aeth_test123"));
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        assertThat(ApiKeyHasher.hash("aeth_a")).isNotEqualTo(ApiKeyHasher.hash("aeth_b"));
    }

    @Test
    void neverReturnsThePlaintextKey() {
        assertThat(ApiKeyHasher.hash("aeth_test123")).doesNotContain("aeth_test123");
    }

    @Test
    void producesA64CharacterHexString() {
        String hash = ApiKeyHasher.hash("aeth_test123");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }
}
