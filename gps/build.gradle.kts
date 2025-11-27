import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.gps"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
}

