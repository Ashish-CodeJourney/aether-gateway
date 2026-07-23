plugins {
    alias(libs.plugins.springBoot)
}

// Standalone Spring Boot application, deliberately not a "gateway-*"
// module and not depending on gateway-core: it simulates an external
// provider, proving the ProviderAdapter boundary (Phase 05) is real, not
// simulated in-process.

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("com.aether.gateway.mockprovider.MockProviderApplication")
}
