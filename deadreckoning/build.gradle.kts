import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.deadreckoning"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Declare sensor permissions (for example BODY_SENSORS) in the app manifest
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Common AndroidX dependencies
    implementation(libs.androidx.core.ktx)
}
