pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Every plugin version lives here, exactly once — every module below applies these
    // plugins WITHOUT a version. All subprojects in one Gradle build share a single plugin
    // classpath; declaring the same Kotlin-family plugin ID with an explicit version in more
    // than one module's own build.gradle.kts fails with "already on the classpath with an
    // unknown version, so compatibility cannot be checked."
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

plugins {
    // Lets `kotlin { jvmToolchain(21) }` (in each module's build.gradle.kts) auto-download a
    // JDK 21 if none is found locally, instead of failing — needed here since only JDK 26 is
    // installed system-wide (Android Studio's own bundled JBR 21 isn't in Gradle's default
    // toolchain-detection search paths).
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "statoscope-kotlin"

include(":testing")
// Android-specific helpers (currently just createAndroidScope — a main-thread-guaranteed
// createScope). Its own module, not folded into the root project, for the same reason
// :testing is: the root project has a standing decision to stay plain Kotlin/JVM with zero
// Android dependency. This module needs kotlinx-coroutines-android (for Dispatchers.Main) but
// nothing from the Android Gradle Plugin — it's still plain kotlin("jvm"), no AGP applied, so
// none of examples/android's AGP/macro-plugin tooling issues apply here.
include(":android")
// examples/todolist/android needed AGP + a Kotlin version old enough to pair with it, which
// dragged this whole build down to Gradle 8.13 and caused real, repeated tooling breakage when
// nested here (see ../statoscope-kotlin-examples/MIGRATION_NOTES.md for the full story). Moved
// examples entirely to a sibling Gradle project, ../statoscope-kotlin-examples/, connected back
// to this library via a composite build (its settings.gradle.kts includeBuild(...)s this one) —
// this project no longer knows examples exist at all.
