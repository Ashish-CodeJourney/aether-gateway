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
    // JDBC driver only because Testcontainers' PostgreSQLContainer opens a
    // JDBC connection to decide the container is ready. Scenarios never
    // read or write the database themselves: keys are created and the
    // request log is read through the admin API.
    testImplementation("org.postgresql:postgresql")
    // No dedicated Testcontainers Redis module exists; AcceptanceEnvironment
    // uses the generic GenericContainer API (part of testcontainers-core
    // above) directly against the redis:8 image instead.
    // Test-tooling only (parsing HTTP response bodies for assertions),
    // not a dependency on any internal module or on gateway wire DTOs.
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun Test.againstTheBuiltJars() {
    dependsOn(":gateway-proxy:bootJar", ":mock-provider:bootJar")
    doFirst {
        systemProperty("gateway.proxy.jar", project(":gateway-proxy").tasks.named("bootJar").get().outputs.files.singleFile.absolutePath)
        systemProperty("mock.provider.jar", project(":mock-provider").tasks.named("bootJar").get().outputs.files.singleFile.absolutePath)
    }
}

// Full mode: the gateway against Testcontainers Postgres and Redis.
tasks.named<Test>("test") {
    againstTheBuiltJars()
    systemProperty("cucumber.filter.tags", "not @lite-only")
}

// ADR-010 lite mode: the same suite against a gateway started with the
// lite profile and no infrastructure at all. Semantic caching does not
// exist in lite mode, so scenarios that expect a SEMANTIC_HIT are skipped.
tasks.register<Test>("acceptanceTestLite") {
    description = "Runs the acceptance suite against a lite-mode gateway (no Postgres, no Redis)."
    group = "verification"
    val testSourceSet = project.extensions.getByType<JavaPluginExtension>().sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    againstTheBuiltJars()
    systemProperty("aether.acceptance.mode", "lite")
    systemProperty("cucumber.filter.tags", "not @requires-semantic-cache")
    shouldRunAfter(tasks.named("test"))
}
