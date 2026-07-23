pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "aether-gateway"

include(
    "gateway-core",
    "gateway-router",
    "gateway-proxy",
    "gateway-providers",
    "gateway-cache",
    "gateway-quota",
    "gateway-registry",
    "gateway-observability",
    "gateway-admin",
    "gateway-bench",
    "mock-provider",
    "gateway-acceptance-tests",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
