dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework:spring-web")
    implementation("org.yaml:snakeyaml")
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.bulkhead)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // WebClient (reactive) for the streaming provider caller, and
    // reactor-core's JdkFlowAdapter to bridge Reactor's Flux to the
    // java.util.concurrent.Flow.Publisher type ChatStreamUseCase (in
    // gateway-core) exposes, keeping gateway-core itself Reactor-free.
    implementation("org.springframework:spring-webflux")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Integration tests (Testcontainers) live in a separate source set
    // per ADR-009 / TESTING-STRATEGY.md, run as a distinct Gradle task
    // from fast unit tests.
    integrationTestImplementation(libs.junit.jupiter)
    integrationTestImplementation(libs.assertj.core)
    integrationTestImplementation(libs.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
