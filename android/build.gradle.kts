plugins {
    kotlin("jvm")
}

group = "statoscope"
version = "0.1.0"

repositories {
    mavenCentral()
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

    testImplementation(project(":testing"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
