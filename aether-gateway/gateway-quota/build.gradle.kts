// QuotaPort and its Redis/Lua implementation, plus the JDBC-backed
// ApiKeyLookupPort implementation: Phase 06 (M3). ADR-007: JdbcClient
// directly, not Spring Data JPA.

dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
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
