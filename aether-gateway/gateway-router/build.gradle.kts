dependencies {
    api(project(":gateway-core"))
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
