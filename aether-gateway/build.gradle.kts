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
}
