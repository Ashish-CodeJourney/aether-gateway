// ProviderAdapter port and its first implementation (MockProviderAdapter)
// land in Phase 05 (M2). Empty in M0/M1.

dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework:spring-web")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
