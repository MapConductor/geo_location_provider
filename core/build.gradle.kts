import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.core"
    compileSdk = 34

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
    // Async processing
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX core utilities (NotificationCompat, etc.)
    implementation(libs.androidx.core.ktx)

    // Dead Reckoning engine, GPS engine and storage
    implementation(project(":deadreckoning"))
    implementation(project(":gps"))
    implementation(project(":storageservice"))
}
