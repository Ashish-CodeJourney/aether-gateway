// Per ADR-009 / TESTING-STRATEGY.md: this module depends ONLY on an HTTP
// test client (java.net.http.HttpClient, part of the JDK, no dependency
// needed) plus Cucumber/JUnit test infrastructure and Testcontainers for
// standing up real infrastructure (Postgres, Redis) the system under
// test needs. It must NEVER depend on gateway-core or any other internal
// module; there is no such project(...) dependency declared below, by
// design, so it is structurally impossible for a scenario step to import
// gateway internals.
//
// The gateway-proxy and mock-provider applications under test are
// launched as separate OS processes (their built boot jars), not as
// in-JVM Spring contexts, so this suite genuinely only ever talks to the
// system over HTTP, exactly as TESTING-STRATEGY.md requires.

dependencies {
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // No dedicated Testcontainers Redis module exists; AcceptanceEnvironment
    // uses the generic GenericContainer API (part of testcontainers-core
    // above) directly against the redis:8 image instead.
    // Test-tooling only (parsing HTTP response bodies for assertions),
    // not a dependency on any internal module or on gateway wire DTOs.
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    dependsOn(":gateway-proxy:bootJar", ":mock-provider:bootJar")
    doFirst {
        systemProperty("gateway.proxy.jar", project(":gateway-proxy").tasks.named("bootJar").get().outputs.files.singleFile.absolutePath)
        systemProperty("mock.provider.jar", project(":mock-provider").tasks.named("bootJar").get().outputs.files.singleFile.absolutePath)
    }
}
