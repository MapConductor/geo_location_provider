plugins {
    // AGP は親の classpath 版を使う（version を書かない）
    id("com.android.library")
    // これらは version catalog の alias を使う
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.mapconductor.plugin.provider.storageservice"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        consumerProguardFiles(file("consumer-rules.pro"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    // ❌ 循環防止のため core へは依存しない
    // implementation(project(":core"))  ← 追加しない

    // Room をこのモジュールで完結させる
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.preferences.v111)

    // コルーチン
    implementation(libs.kotlinx.coroutines.core)
}

room {
    // Room スキーマ出力先
    schemaDirectory("$projectDir/schemas")
}
