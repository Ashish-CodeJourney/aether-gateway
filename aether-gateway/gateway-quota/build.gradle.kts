// QuotaPort and its Redis/Lua implementation: Phase 06 (M3).

dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
