pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "astock-exchange"

include("astock-domain")
include("astock-service")
include("astock-paper")
