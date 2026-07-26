plugins {
    id("java")
    alias(libs.plugins.springDependencyManagement) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
            mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
            mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
        }
        // Spring Boot 4.1.0's own BOM pins Netty 4.2.15.Final, which has
        // 3 real HIGH-severity CVEs Trivy catches in CI (an infinite-loop
        // DoS in Bzip2Decoder, CVE-2026-59901, plus two more in the HTTP
        // codecs) - all fixed in 4.2.16.Final. Overriding here rather
        // than waiting for Spring Boot's own BOM to catch up.
        dependencies {
            dependency("io.netty:netty-codec-compression:4.2.16.Final")
            dependency("io.netty:netty-codec-http:4.2.16.Final")
            dependency("io.netty:netty-codec-http3:4.2.16.Final")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Phase 09 (M6) experiment 3 loads a second, larger ONNX embedding
        // model (all-mpnet-base-v2, 768d) alongside the default MiniLM-L6 -
        // the JVM test worker's default heap is too small for both.
        maxHeapSize = "2g"
    }

    // ADR-009 / TESTING-STRATEGY.md: integration tests (Testcontainers,
    // real Postgres/Redis) live in their own source set, run as a
    // separate Gradle task from fast unit tests so CI can report and
    // gate on them distinctly.
    val sourceSets = the<JavaPluginExtension>().sourceSets
    val integrationTest = sourceSets.create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += output + compileClasspath
    }

    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests (Testcontainers, real infra)."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.named("test"))
    }

    tasks.named("check") {
        dependsOn(tasks.named("integrationTest"))
    }

    // AC9 (PRD 4.1): line coverage on core modules >= 75%. Merges unit
    // and integration test execution data, since a meaningful slice of
    // coverage (e.g. gateway-cache's real Postgres/pgvector paths) only
    // ever executes in the integrationTest source set.
    tasks.register<JacocoReport>("jacocoMergedReport") {
        dependsOn(tasks.named("test"), tasks.named("integrationTest"))
        executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
        sourceSets(sourceSets["main"])
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
