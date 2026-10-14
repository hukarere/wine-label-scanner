plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// Desktop command line (scan / eval photos on the JVM) — never part of the app.
val cli: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    // Same `ai.onnxruntime` API on desktop and Android: the app supplies onnxruntime-android,
    // the desktop tools and tests use the desktop build.
    compileOnly(libs.onnxruntime.jvm)
    "cliImplementation"(libs.onnxruntime.jvm)
    "cliImplementation"(libs.serialization.json)
    testImplementation(cli.output)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot = rootProject.projectDir.parentFile
val models = rootProject.file("app/src/main/assets/models")

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Test fixtures shared with the app's on-device test; same model files as the app.
    systemProperty("winelabel.fixtures", repoRoot.resolve("fixtures").absolutePath)
    systemProperty("winelabel.models", models.absolutePath)
    // Photo folder under fixtures/ for PpOcrParityTest, e.g. -Pphotos=vinmonopolet (local-only set).
    systemProperty("winelabel.photos", (findProperty("photos") as String?) ?: "images")
    systemProperty("winelabel.dump", (findProperty("dump") as String?) ?: "")
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Desktop OCR: ./gradlew -q :core:cli --args=\"scan <photo> | eval <folder>\" (paths from the repo root)"
    classpath = cli.runtimeClasspath
    mainClass.set("winelabel.cli.MainKt")
    workingDir = repoRoot
    maxHeapSize = "2g"
    systemProperty("winelabel.models", models.absolutePath)
}
