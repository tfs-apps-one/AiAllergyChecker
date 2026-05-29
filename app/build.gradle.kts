plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tfsapps.aiallergychecker"
    compileSdk = 35

    defaultConfig {
        applicationId = "tfsapps.aiallergychecker"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 16 KB ページサイズ対応
    // ネイティブライブラリ(.so)を APK/AAB 内に圧縮せずそのまま格納し、
    // OS が 16 KB 境界でマッピングできるようにする。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ML Kit – Japanese Text Recognition
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}