import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Real production release signing. Credentials live in a root-level keystore.properties
// (never committed — see .gitignore) rather than in this file. That file, and the .jks
// it points at, are Adi's sole responsibility to back up securely outside this repo —
// losing either blocks all future updates to whatever's already installed on client
// devices (side-loaded via EnjoyKit, so there's no Play Store re-issue safety net).
val terminalKeystorePropertiesFile = rootProject.file("keystore.properties")
val terminalKeystoreProperties = Properties().apply {
    if (terminalKeystorePropertiesFile.exists()) {
        FileInputStream(terminalKeystorePropertiesFile).use { load(it) }
    } else {
        throw GradleException(
            "Missing ${terminalKeystorePropertiesFile.path} — required for terminalApp release " +
                "signing (TERMINAL_STORE_FILE/TERMINAL_STORE_PASSWORD/TERMINAL_KEY_ALIAS/" +
                "TERMINAL_KEY_PASSWORD). This file is gitignored and never committed; ask Adi " +
                "for the production keystore.properties + matching .jks file."
        )
    }
}

android {
    namespace = "com.ekms.terminal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ekms.terminal"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(terminalKeystoreProperties.getProperty("TERMINAL_STORE_FILE"))
            storePassword = terminalKeystoreProperties.getProperty("TERMINAL_STORE_PASSWORD")
            keyAlias = terminalKeystoreProperties.getProperty("TERMINAL_KEY_ALIAS")
            keyPassword = terminalKeystoreProperties.getProperty("TERMINAL_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material3)
    // Icon glyphs for IconActionButton and the login method tiles (Phase 9 design-system
    // rework). Started on material-icons-core (Phase 9A), swapped to material-icons-extended
    // (Phase 9A hardware-bug fix) because Fingerprint/Face/Nfc/VpnKey aren't in core — only
    // the ~20-icon base set (Close/Check/Add etc.) is. Flagged size tradeoff: extended is a
    // much larger artifact (bundles the full classic Material icon set, several MB vs core's
    // near-nothing) — accepted because the specific glyphs the login screen needs live there.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(files("libs/serialport.aar"))

    // Face enrollment (Part C, decision-independent pieces only — see CLAUDE.md).
    // Versions match ../eKMSHardwareTester exactly, pending native-compatibility
    // verification against this module's pinned AGP 8.11.1/compileSdk 36 baseline.
    implementation("org.opencv:opencv:5.0.0.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
