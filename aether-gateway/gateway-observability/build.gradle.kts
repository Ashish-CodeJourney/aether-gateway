// RequestLogPort, MetricsPort, and CostModelPort implementations: Phase 08 (M5).
// ADR-009: cost model math itself lives in gateway-core as pure logic;
// this module only supplies persistence, metric emission, and config
// loading, all adapter concerns.

dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-core")
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation("org.flywaydb:flyway-core")
    integrationTestImplementation("org.flywaydb:flyway-database-postgresql")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
