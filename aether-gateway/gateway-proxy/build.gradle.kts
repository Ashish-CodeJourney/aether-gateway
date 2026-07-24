plugins {
    alias(libs.plugins.springBoot)
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":gateway-router"))
    implementation(project(":gateway-providers"))
    implementation(project(":gateway-cache"))
    implementation(project(":gateway-quota"))
    implementation(project(":gateway-registry"))
    implementation(project(":gateway-observability"))
    implementation(project(":gateway-admin"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.bulkhead)
    // Phase 08 (M5): F6.1 metrics (Prometheus-scraped via /actuator/prometheus)
    // and F6.5 tracing.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-logging")
    // Spring Boot 4's modular autoconfigure split Flyway support into its
    // own module (spring-boot-flyway), no longer pulled in transitively
    // by spring-boot-starter-jdbc; without it FlywayAutoConfiguration is
    // never even a candidate, and migrations silently never run.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("com.aether.gateway.proxy.AetherGatewayApplication")
}

// db/migrations/ at the repo root is the single source of truth for the
// schema (docs/plan/02-architecture-and-design.md task 2); copy it onto
// the classpath under Flyway's expected db/migration convention rather
// than duplicating the SQL files inside this module.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.projectDir.parentFile.resolve("db/migrations")) {
        into("db/migration")
    }
}

