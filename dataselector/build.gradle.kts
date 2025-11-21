plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.ksp)   // KSP を利用する場合に有効化する
    // alias(libs.plugins.room)  // Room を直接利用する場合のみ有効化する
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.dataselector"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":storageservice"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
