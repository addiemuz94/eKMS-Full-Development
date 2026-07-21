pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "eKMS"

include(":shared")
include(":terminalApp")
include(":mobileApp")
include(":webApp")