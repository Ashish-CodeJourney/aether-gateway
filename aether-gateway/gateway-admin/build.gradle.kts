dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":gateway-router"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
