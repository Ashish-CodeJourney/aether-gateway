package com.aether.gateway.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-010: lite mode's in-memory adapters are plain JDK code, and lite
 * mode's wiring can never quietly pull a Postgres, Redis or embedding
 * dependency back in.
 */
class LiteModeArchitectureTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aether.gateway");
    }

    @Test
    void inMemoryAdaptersAreFrameworkFree() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.aether.gateway..inmemory..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "io.lettuce..",
                        "java.sql..",
                        "javax.sql..",
                        "org.postgresql..",
                        "org.flywaydb..",
                        "tools.jackson..",
                        "ai.onnxruntime..")
                .check(allClasses);
    }

    @Test
    void liteWiringNeverReferencesInfrastructureAdapters() {
        ArchRuleDefinition.noClasses()
                .that().haveSimpleName("LiteModeInfrastructureConfig")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("Jdbc")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("Redis")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("PgVector")
                .orShould().dependOnClassesThat().haveSimpleName("EmbeddingGenerator")
                .orShould().dependOnClassesThat().haveSimpleName("CacheAdapter")
                .orShould().dependOnClassesThat().haveSimpleName("StringRedisTemplate")
                .orShould().dependOnClassesThat().haveSimpleName("JdbcClient")
                .check(allClasses);
    }
}
