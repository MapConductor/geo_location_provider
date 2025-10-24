plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.ksp) // ← KSPを使っていないなら消してOK
    // alias(libs.plugins.room) // ← 外す（原因行）
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
