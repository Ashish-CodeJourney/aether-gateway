// Load generator and eval harness: Phase 09 (M6).

dependencies {
    api(project(":gateway-core"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Experiment runners only (real embedding model against the real corpus).
    integrationTestImplementation(project(":gateway-cache"))
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
