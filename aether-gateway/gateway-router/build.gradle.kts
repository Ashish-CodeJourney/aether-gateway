dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework:spring-web")
    // WebClient (reactive) for the streaming provider caller, and
    // reactor-core's JdkFlowAdapter to bridge Reactor's Flux to the
    // java.util.concurrent.Flow.Publisher type ChatStreamUseCase (in
    // gateway-core) exposes, keeping gateway-core itself Reactor-free.
    implementation("org.springframework:spring-webflux")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
