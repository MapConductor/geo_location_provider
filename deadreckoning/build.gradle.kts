import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.deadreckoning"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // センサー権限（BODY_SENSORS など）はアプリ側のマニフェストで宣言してください
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
    // コルーチン
    implementation(libs.kotlinx.coroutines.android)

    // 共通 AndroidX 依存
    implementation(libs.androidx.core.ktx)
}
