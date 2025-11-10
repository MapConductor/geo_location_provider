plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // --- 重要：今回の未解決参照の解消に必要 ---
    // FusedLocationProvider 等
    implementation(libs.play.services.location)
    // NotificationCompat, ContextCompat など
    implementation(libs.androidx.core.ktx)

    // コルーチン
    implementation(libs.kotlinx.coroutines.android)

    // DeadReckoning engine（今回追加）
    implementation(project(":deadreckoning"))
    implementation(project(":storageservice"))
}
