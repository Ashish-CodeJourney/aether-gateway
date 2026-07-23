// CachePort and its pgvector/Redis implementation land in Phase 07 (M4).
// Out of scope for M0-M3; stub module only, kept in the build so the
// module list matches PRD section 6 from the first commit.

dependencies {
    api(project(":gateway-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
