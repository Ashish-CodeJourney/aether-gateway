// gateway-core: the hexagon. Depends on nothing but the JDK in the main
// source set (ADR-001). Test scope pulls in every other module purely so
// the ArchUnit suite below can scan their compiled classes; this does not
// affect the production JAR, which stays dependency-free.

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(project(":gateway-router"))
    testImplementation(project(":gateway-proxy"))
    testImplementation(project(":gateway-providers"))
    testImplementation(project(":gateway-cache"))
    testImplementation(project(":gateway-quota"))
    testImplementation(project(":gateway-registry"))
    testImplementation(project(":gateway-observability"))
    testImplementation(project(":gateway-admin"))
}
