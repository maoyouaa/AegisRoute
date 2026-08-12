pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "aegis-route"

include(
    "apps:gateway",
    "apps:control",
    "apps:worker",
    "apps:mock-provider",
    "modules:domain",
    "modules:contracts",
    "modules:provider-spi",
)
