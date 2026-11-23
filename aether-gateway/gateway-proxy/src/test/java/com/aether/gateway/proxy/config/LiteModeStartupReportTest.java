package com.aether.gateway.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-010: lite mode states its limits once, loudly, at startup. */
class LiteModeStartupReportTest {

    @Test
    void theWarningListsEveryUnsupportedFeature() {
        String warning = LiteModeStartupReport.unsupportedFeaturesWarning(Map.of());

        assertThat(warning)
                .contains("single replica")
                .contains("restart")
                .contains("semantic cache")
                .contains("ring buffer")
                .contains("breaker hints")
                .contains("migrat");
        assertThat(warning).doesNotContain("Kubernetes");
    }

    @Test
    void theWarningIsLouderUnderKubernetes() {
        String warning = LiteModeStartupReport.unsupportedFeaturesWarning(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"));

        assertThat(warning).contains("Kubernetes").contains("replicas: 1").contains("Recreate");
    }

    @Test
    void theGeneratedAdminKeyIsAnnouncedOnceWithHowToPinIt() {
        String announcement = LiteModeStartupReport.generatedAdminKeyAnnouncement("aeth_admin_secret");

        assertThat(announcement).contains("aeth_admin_secret").contains("AETHER_ADMIN_API_KEY");
    }
}
