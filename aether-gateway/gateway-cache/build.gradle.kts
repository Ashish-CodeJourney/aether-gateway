// CachePort and its pgvector/Redis implementation: Phase 07 (M4).
// ADR-007: Spring AI is used only for the embedding model itself
// (spring-ai-transformers, in-process ONNX, F4.3); the vector store is
// hand-built on JdbcClient, not Spring AI's VectorStore abstraction.

dependencies {
    api(project(":gateway-core"))
    implementation(libs.spring.ai.transformers)
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
