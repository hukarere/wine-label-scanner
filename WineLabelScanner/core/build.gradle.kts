plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    // Same `ai.onnxruntime` API on desktop and Android: the app supplies onnxruntime-android,
    // tests use the desktop build.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
}

val pyProject = rootProject.file("../wine-label-py")

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Shared with the Python prototype: same fixtures, same model files.
    systemProperty("winelabel.fixtures", pyProject.resolve("fixtures").absolutePath)
    systemProperty("winelabel.models", rootProject.file("app/src/main/assets/models").absolutePath)
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
