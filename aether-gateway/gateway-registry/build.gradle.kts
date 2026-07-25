// PromptRegistryPort and its Postgres implementation: Phase 10 (M7).

dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    integrationTestImplementation(libs.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation("org.flywaydb:flyway-core")
    integrationTestImplementation("org.flywaydb:flyway-database-postgresql")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
