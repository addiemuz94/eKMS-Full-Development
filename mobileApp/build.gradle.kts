import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
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
    implementation(libs.androidx.compose.material.icons.extended)
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
}
