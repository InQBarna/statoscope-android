plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = property("GROUP") as String
version = property("VERSION") as String

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`, not `implementation`: createAndroidScope's own signature is ScopeConfig -> ScopeStore,
    // both statoscope.core types — a consumer needs them visible too, same reasoning as :testing.
    api(project(":"))
    // For Dispatchers.Main. Pure JVM artifact at compile time (no android.* imports needed to
    // build against it) — only *running* on it needs a real Android Looper, which is exactly
    // why scopeEffectsDisabledInUnitTests (see ScopeStore.kt) matters here: a JUnit test calling
    // createAndroidScope must never actually launch anything on Dispatchers.Main, since no real
    // Looper exists in that context.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose RUNTIME only (@Composable, State<T>, collectAsState) — deliberately not
    // compose-ui/material3/activity-compose, which pull in the Android framework and need AGP.
    // This module stays plain Kotlin/JVM (see MIGRATION_NOTES.md in statoscope-kotlin-examples
    // for why AGP doesn't mix well inside this build) — androidx.compose.runtime itself is a
    // pure Kotlin/JVM (Compose Multiplatform) artifact, no Android dependency needed to compile
    // against it. The Kotlin Compose compiler plugin above is likewise AGP-independent.
    api("androidx.compose.runtime:runtime:1.9.4")

    testImplementation(project(":testing"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Drives ConnectedScreen/ConnectedChild (plain @Composable functions) off a Recomposer with
    // no Android framework and no compose-ui — exactly the gap this module's own doc comments
    // flag (test-parity review: these had zero unit tests despite needing no device/emulator).
    // Pure Kotlin/JVM, like androidx.compose.runtime itself; no AGP or compose-ui pulled in.
    testImplementation("app.cash.molecule:molecule-runtime:2.0.0")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
