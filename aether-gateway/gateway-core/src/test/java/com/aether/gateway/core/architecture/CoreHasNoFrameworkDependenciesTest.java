package com.aether.gateway.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-001: gateway-core depends on nothing but the JDK. This is the
 * mechanical enforcement of that rule; it must fail the build the moment
 * any class under com.aether.gateway.core imports Spring, Reactor,
 * Jackson, or any other adapter module.
 */
class CoreHasNoFrameworkDependenciesTest {

    private static JavaClasses coreClasses;

    @BeforeAll
    static void importClasses() {
        coreClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aether.gateway.core");
    }

    @Test
    void coreImportsNoSpringFramework() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .check(coreClasses);
    }

    @Test
    void coreImportsNoReactor() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAPackage("reactor..")
                .check(coreClasses);
    }

    @Test
    void coreImportsNoJacksonOrOtherThirdPartyLibraries() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.fasterxml.jackson..",
                        "io.github.resilience4j..",
                        "org.testcontainers..",
                        "io.cucumber..")
                .check(coreClasses);
    }

    @Test
    void coreDoesNotDependOnAnyAdapterModule() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aether.gateway.router..",
                        "com.aether.gateway.proxy..",
                        "com.aether.gateway.providers..",
                        "com.aether.gateway.cache..",
                        "com.aether.gateway.quota..",
                        "com.aether.gateway.registry..",
                        "com.aether.gateway.observability..",
                        "com.aether.gateway.admin..",
                        "com.aether.gateway.bench..",
                        "com.aether.gateway.mockprovider..")
                .check(coreClasses);
    }
}
