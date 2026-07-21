plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.datetime)
        }
    }
}
