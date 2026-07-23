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
// Website portal is now React in /web (see web/README.md). Kotlin/Wasm webApp is frozen.
// include(":webApp")
