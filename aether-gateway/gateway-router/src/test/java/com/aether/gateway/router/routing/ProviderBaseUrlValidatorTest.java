package com.aether.gateway.router.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderBaseUrlValidatorTest {

    private final ProviderBaseUrlValidator validator = new ProviderBaseUrlValidator();

    @Test
    void rejectsABaseUrlHostThatIsALiteralPrivateIpAddress() {
        assertThatThrownBy(() -> validator.validate("evil", "http://10.0.0.5:8080"))
                .isInstanceOf(SsrfProtectionException.class)
                .hasMessageContaining("evil")
                .hasMessageContaining("10.0.0.5");
    }

    @Test
    void rejectsLoopback() {
        assertThatThrownBy(() -> validator.validate("evil", "http://127.0.0.1:8080"))
                .isInstanceOf(SsrfProtectionException.class);
    }

    @Test
    void allowsAGenuinelyPublicLiteralIpAddress() {
        assertThatCode(() -> validator.validate("real-provider", "https://8.8.8.8"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnparseableUrl() {
        assertThatThrownBy(() -> validator.validate("broken", "not a url"))
                .isInstanceOf(SsrfProtectionException.class);
    }
}
