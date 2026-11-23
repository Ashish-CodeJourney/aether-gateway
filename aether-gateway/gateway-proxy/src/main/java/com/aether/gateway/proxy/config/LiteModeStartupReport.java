package com.aether.gateway.proxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * ADR-010: lite mode states what it does not do, once, as a WARN at
 * startup - louder when it can tell it is running under Kubernetes,
 * where scaling past one replica or a rolling deploy is one edit away.
 * If the admin key was generated for this run, it is logged here, once.
 */
public class LiteModeStartupReport {

    private static final Logger log = LoggerFactory.getLogger(LiteModeStartupReport.class);

    private final Environment environment;
    private final Map<String, String> processEnvironment;

    public LiteModeStartupReport(Environment environment, Map<String, String> processEnvironment) {
        this.environment = environment;
        this.processEnvironment = processEnvironment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.warn(unsupportedFeaturesWarning(processEnvironment));
        if (environment.getProperty(LiteAdminKeyEnvironmentPostProcessor.GENERATED_MARKER, Boolean.class, false)) {
            log.warn(generatedAdminKeyAnnouncement(environment.getProperty("aether.admin.api-key")));
        }
    }

    static String unsupportedFeaturesWarning(Map<String, String> processEnvironment) {
        String warning = """
                Aether is running in LITE mode: every store is in process memory. Not supported in lite mode: \
                (1) more than a single replica, or overlapping rolling deploys - limits are enforced per process; \
                (2) durability - API keys, prompts, the request log and monthly budgets reset on every restart, \
                so a restart can allow budget overspend; \
                (3) the semantic cache - caching is exact-match only; \
                (4) usage history beyond the in-memory ring buffer of recent requests; \
                (5) breaker hints shared between replicas; \
                (6) migrating lite data to full mode. \
                Use full mode (Postgres + Redis) for anything you cannot afford to lose.""";
        if (!processEnvironment.containsKey("KUBERNETES_SERVICE_HOST")) {
            return warning;
        }
        return "!!! " + warning + " Running under Kubernetes: keep replicas: 1 and use the Recreate deployment strategy, "
                + "or switch to full mode. !!!";
    }

    static String generatedAdminKeyAnnouncement(String adminKey) {
        return "No AETHER_ADMIN_API_KEY was set, so this run generated an admin key (shown once, valid until restart): "
                + adminKey + " - set AETHER_ADMIN_API_KEY to choose a stable one.";
    }
}
