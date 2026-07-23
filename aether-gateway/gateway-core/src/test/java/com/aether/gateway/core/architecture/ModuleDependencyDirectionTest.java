package com.aether.gateway.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the dependency direction rules from
 * docs/design/module-boundaries.md, beyond the core-specific rules in
 * CoreHasNoFrameworkDependenciesTest. Classes are imported from every
 * module's compiled output because this test lives in gateway-core's
 * test scope with testImplementation project dependencies on every other
 * module (see gateway-core/build.gradle.kts) purely to make their
 * classes available to scan; this does not affect gateway-core's
 * production jar.
 *
 * <p>allowEmptyShould(true) is used throughout: several driven-adapter
 * modules (gateway-cache, gateway-registry, gateway-observability,
 * gateway-bench) are still empty stubs at this phase (their content
 * lands in Phase 07, Phase 10, Phase 08, Phase 09 respectively). A rule
 * scanning an empty package must not fail the build; it must still run
 * and start enforcing the moment real classes appear there.
 */
class ModuleDependencyDirectionTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aether.gateway");
    }

    @Test
    void driverAdaptersDoNotDependOnEachOther() {
        // gateway-admin and gateway-proxy are both driving adapters; admin
        // must not depend on proxy (proxy is the assembly root, per
        // ADR-001, not the other way around).
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.aether.gateway.admin..")
                .should().dependOnClassesThat().resideInAPackage("com.aether.gateway.proxy..")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void drivenAdaptersDoNotDependOnDrivingAdaptersOrRouter() {
        String[] drivenAdapterPackages = {
                "com.aether.gateway.providers..",
                "com.aether.gateway.cache..",
                "com.aether.gateway.quota..",
                "com.aether.gateway.registry..",
                "com.aether.gateway.observability..",
        };
        for (String pkg : drivenAdapterPackages) {
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage(pkg)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.aether.gateway.router..",
                            "com.aether.gateway.proxy..",
                            "com.aether.gateway.admin..")
                    .allowEmptyShould(true)
                    .check(allClasses);
        }
    }

    @Test
    void drivenAdaptersDoNotDependOnEachOther() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.aether.gateway.cache..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aether.gateway.quota..",
                        "com.aether.gateway.registry..",
                        "com.aether.gateway.observability..",
                        "com.aether.gateway.providers..")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void routerDependsOnlyOnCoreAmongInternalGatewayModules() {
        // gateway-router is application/orchestration, sitting just inside
        // the hexagon boundary. It may depend on gateway-core (the ports
        // it implements and calls) and nothing else internal: not the
        // driven adapters it talks to only through ports, not the driving
        // adapters that call it. RoutingPolicyRepository's adapterFactory
        // pattern exists specifically so gateway-router never needs a
        // direct MockProviderAdapter (or any concrete adapter) reference.
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.aether.gateway.router..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aether.gateway.providers..",
                        "com.aether.gateway.cache..",
                        "com.aether.gateway.quota..",
                        "com.aether.gateway.registry..",
                        "com.aether.gateway.observability..",
                        "com.aether.gateway.proxy..",
                        "com.aether.gateway.admin..",
                        "com.aether.gateway.mockprovider..")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void mockProviderHasNoInternalGatewayDependency() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.aether.gateway.mockprovider..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aether.gateway.core..",
                        "com.aether.gateway.router..",
                        "com.aether.gateway.proxy..",
                        "com.aether.gateway.providers..",
                        "com.aether.gateway.cache..",
                        "com.aether.gateway.quota..",
                        "com.aether.gateway.registry..",
                        "com.aether.gateway.observability..",
                        "com.aether.gateway.admin..")
                .allowEmptyShould(true)
                .check(allClasses);
    }
}
