plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "winelabel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "winelabel.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Virtually all phones since ~2017 are arm64; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Models are memory-mapped/read faster when stored uncompressed.
    androidResources { noCompress += listOf("onnx") }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.exifinterface)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
