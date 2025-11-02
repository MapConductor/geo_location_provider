plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)           // Room など使うなら
    alias(libs.plugins.room)          // Room プラグインを使っている場合
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

    // Room を使うなら schemaDirectory を必ず設定
    room {
        schemaDirectory("$projectDir/schemas")
    }
}

dependencies {
    // --- 重要：今回の未解決参照の解消に必要 ---
    // FusedLocationProvider 等
    implementation(libs.play.services.location)
    // NotificationCompat, ContextCompat など
    implementation(libs.androidx.core.ktx)

    // Room（core に置いている前提）
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // コルーチン
    implementation(libs.kotlinx.coroutines.android)

    // DeadReckoning engine（今回追加）
    implementation(project(":deadreckoning"))
}
