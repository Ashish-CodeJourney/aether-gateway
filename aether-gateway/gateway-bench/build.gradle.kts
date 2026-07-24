// Load generator and eval harness: Phase 09 (M6).

dependencies {
    api(project(":gateway-core"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Experiment runners only (real embedding model against the real corpus).
    integrationTestImplementation(project(":gateway-cache"))
    // Experiment 3 (embedding model comparison) constructs a second,
    // non-default TransformersEmbeddingModel directly - EmbeddingGenerator
    // (gateway-cache) only ever wires up the product default (MiniLM-L6).
    integrationTestImplementation(libs.spring.ai.transformers)
    // Experiment 4 (HNSW parameter tuning) needs direct pgvector index
    // control (m, ef_search) against a real Testcontainers Postgres,
    // via its own purpose-built table - not PgVectorCacheStore's schema.
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    integrationTestRuntimeOnly("org.postgresql:postgresql")
    integrationTestImplementation(libs.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
