plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mapconductor.plugin.provider.geolocation.auth.credentialmanager"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Depend on datamanager for GoogleDriveTokenProvider interface
    implementation(project(":datamanager"))

    // Credential Manager (Android 14+)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    // Google Sign-In with Credential Manager
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // AuthorizationClient for OAuth scopes (Google Drive access)
    implementation("com.google.android.gms:play-services-auth:21.4.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
