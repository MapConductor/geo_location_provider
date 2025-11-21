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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 位置情報取得に必要な依存関係
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)

    // 非同期処理
    implementation(libs.kotlinx.coroutines.android)

    // Dead Reckoning エンジンと記録用ストレージ
    implementation(project(":deadreckoning"))
    implementation(project(":storageservice"))
}

