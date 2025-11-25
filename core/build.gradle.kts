import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.core"
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
    // Dependencies required for location acquisition
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)

    // Async processing
    implementation(libs.kotlinx.coroutines.android)

    // Dead Reckoning engine and storage
    implementation(project(":deadreckoning"))
    implementation(project(":storageservice"))
}

