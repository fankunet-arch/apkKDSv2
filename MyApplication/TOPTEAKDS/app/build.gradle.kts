/*
 * 文件名: app/build.gradle.kts (Module :app)
 * 描述: (已添加 MultiDex 解决方案)
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toptea.topteakds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toptea.topteakds"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ========================================================
        //  !!! 唯一的修改在这里 !!!
        //  添加这一行来解决 mergeDexRelease 错误
        // ========================================================
        multiDexEnabled = true
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 1. WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // 2. Coroutines (用于后台执行打印)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // 3. CameraX (规范 8.2 要求的相机库)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 4. Google ML Kit (规范 8.2 要求的扫码库)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // 5. Google Play Services Location (For GPS check)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 6. Exif Interface (For writing GPS data to JPG)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 7. Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}