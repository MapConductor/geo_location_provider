plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.deadreckoning"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // センサー使用のためのマニフェスト権限はアプリ側に記載してください
        // <uses-permission android:name="android.permission.BODY_SENSORS"/>
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX
    implementation(libs.androidx.core.ktx)
}
