import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.ksp)   // Enable when using KSP
    // alias(libs.plugins.room)  // Enable only if this module uses Room directly
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.dataselector"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // Project modules
    implementation(project(":storageservice"))

    // Coroutines / preferences
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}

