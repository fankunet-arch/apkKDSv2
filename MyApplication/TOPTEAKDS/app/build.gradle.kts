/*
 * 文件名: app/build.gradle.kts (Module :app)
 * 描述: Configured with SDK 35, Version Catalog, debug signing for release builds
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.toptea.topteakds"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toptea.topteakds"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Requested fix: MultiDex enabled
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // !!! CRITICAL FIX !!!
            // Use debug signing to prevent build failure due to incorrect release keystore password.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity) // Required for enableEdgeToEdge
    implementation(libs.androidx.constraintlayout)
    implementation(libs.zxing.core)

    // 1. WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // 2. Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // 3. CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 4. Google ML Kit
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // 5. Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 6. Exif Interface
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
