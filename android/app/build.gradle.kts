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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // On-device test (DeviceScanTest) reads the shared photo fixtures as test-APK assets.
    // -Pphotos=vinmonopolet packages the local-only set instead of fixtures/images.
    val photos = (findProperty("photos") as String?) ?: "images"
    sourceSets["androidTest"].assets.srcDir(rootProject.file("../python/fixtures/$photos"))
    // photoset.txt tells DeviceScanTest which set it got (accuracy floors exist only for "images").
    val photoSetDir = layout.buildDirectory.dir("generated/photoset").get().asFile
    photoSetDir.resolve("photoset.txt").apply { parentFile.mkdirs(); writeText(photos) }
    sourceSets["androidTest"].assets.srcDir(photoSetDir)

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

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
