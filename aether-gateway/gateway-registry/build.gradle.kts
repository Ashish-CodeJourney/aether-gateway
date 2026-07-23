// PromptRegistryPort and its Postgres implementation: Phase 10 (M7).
// Out of scope for M0-M3; stub module only.

dependencies {
    api(project(":gateway-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
