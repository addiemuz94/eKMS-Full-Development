import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

// Real production release signing. Credentials live in a root-level keystore.properties
// (never committed — see .gitignore) rather than in this file. That file, and the .jks
// it points at, are Adi's sole responsibility to back up securely outside this repo —
// losing either blocks all future updates to whatever's already installed on client
// devices.
val mobileKeystorePropertiesFile = rootProject.file("keystore.properties")
val mobileKeystoreProperties = Properties().apply {
    if (mobileKeystorePropertiesFile.exists()) {
        FileInputStream(mobileKeystorePropertiesFile).use { load(it) }
    } else {
        throw GradleException(
            "Missing ${mobileKeystorePropertiesFile.path} — required for mobileApp release " +
                "signing (MOBILE_STORE_FILE/MOBILE_STORE_PASSWORD/MOBILE_KEY_ALIAS/" +
                "MOBILE_KEY_PASSWORD). This file is gitignored and never committed; ask Adi " +
                "for the production keystore.properties + matching .jks file."
        )
    }
}

android {
    namespace = "com.ekms.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ekms.mobile"
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
            storeFile = rootProject.file(mobileKeystoreProperties.getProperty("MOBILE_STORE_FILE"))
            storePassword = mobileKeystoreProperties.getProperty("MOBILE_STORE_PASSWORD")
            keyAlias = mobileKeystoreProperties.getProperty("MOBILE_KEY_ALIAS")
            keyPassword = mobileKeystoreProperties.getProperty("MOBILE_KEY_PASSWORD")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    // Bottom-nav/overflow-menu/action-button icons (see CLAUDE_MOBILE.md's mobileApp UX rework
    // Phase M-D) — replaces material-icons-extended, mobileApp-only, matching web's lucide-react.
    implementation(libs.lucide.icons.android)
    // Glassmorphism on the overflow menu (M-A) and ConfirmDialog (M-C) only — see
    // CLAUDE_MOBILE.md's mobileApp UX rework Phase M-E.
    implementation(libs.haze)
    implementation(libs.haze.materials)
    // Boot splash (see CLAUDE_MOBILE.md) — platform-native icon-reveal step for API 31+.
    implementation(libs.androidx.core.splashscreen)
    // Biometric login (see CLAUDE_MOBILE.md) — encrypts/decrypts the locally-stored refresh
    // token behind a BIOMETRIC_STRONG-gated AndroidKeyStore key. fragment-ktx pinned explicitly
    // (not left to biometric's own old 1.2.5 transitive pull) because MainActivity now extends
    // FragmentActivity, which BiometricPrompt's real constructor requires.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    // Mirrors terminalApp's exact HTTP client choice (Ktor client + OkHttp engine +
    // kotlinx.serialization content negotiation) — mobileApp is Android-only like terminalApp
    // (not Wasm-targeted), so there's no cross-platform-engine constraint pushing toward a
    // different client; reusing the same one terminalApp already depends on (rather than
    // introducing e.g. Retrofit) keeps exactly one HTTP stack across the two Android apps.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Real push notifications for checkout-deadline alerts (see CLAUDE_MOBILE.md).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Terminals-tab map (see CLAUDE_MOBILE.md) — native View-based OSM map, replacing the
    // WebView+Leaflet approach that hit an unresolved Chromium tile-compositor bug.
    implementation(libs.osmdroid.android)
}
