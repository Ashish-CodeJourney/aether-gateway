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
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
