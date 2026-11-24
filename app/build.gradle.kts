plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yudha.komik"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yudha.komik"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // API key ditanam saat build dari GitHub Secret "NH_API_KEY" (tidak pernah ditulis di kode).
        buildConfigField("String", "NH_API_KEY", "\"${System.getenv("NH_API_KEY") ?: ""}\"")
    }

    buildFeatures { buildConfig = true }

    // Keystore tetap, supaya APK hasil build baru bisa langsung menimpa versi lama (update tanpa uninstall).
    signingConfigs {
        getByName("debug") {
            storeFile = file("komik-debug.jks")
            storePassword = "android"
            keyAlias = "komik"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("io.coil-kt:coil:2.6.0")
    // Mesin jaringan Chrome (Cronet) dari Google Play Services, untuk memuat gambar seperti di browser
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("org.chromium.net:cronet-api:119.6045.31")
    // Terjemahan: baca teks di gambar + terjemahkan offline (Google ML Kit)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
