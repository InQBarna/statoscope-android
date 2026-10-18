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
        // KSP's own versioning dropped the "<kotlin>-<ksp>" combined scheme somewhere around
        // Kotlin 2.3 — plain releases now, not required to match the Kotlin version exactly.
        // Verified compatible with 2.3.20 above by actually building and running against it
        // (see :codegen-processor's self-test) before pinning this.
        id("com.google.devtools.ksp") version "2.3.12"
        // :android's Compose integration helpers (feature/compose-integration branch) — paired
        // with the exact Kotlin version above, same convention as every other plugin here.
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
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
// The @Reducer-driven config generator: @Reducer/@SubState/@OnChildAction annotations (:codegen)
// plus the KSP SymbolProcessor that reads them and generates <Reducer>.config — the
// ScopeConfig(...)/SubScope(...)/get/set boilerplate a hand-written Tutorial 05-style reducer
// tree otherwise needs. Two modules, matching KSP's own annotations/processor split (and Room's,
// Moshi's, etc.): :codegen has zero dependencies (pure marker annotations, usable without KSP at
// all); :codegen-processor is only ever needed on a module's `ksp` configuration, never
// `implementation`. Neither depends on the root project — the processor validates/emits against
// fully-qualified name strings, never actual Kotlin types from here, so applying KSP downstream
// (an app module) needs no build-time link back to this repo beyond the ordinary
// "com.inqbarna.statoscope:statoscope-kotlin" dependency the generated code itself expects at
// compile time. Neither is applied to any module in this build — like :testing/:android, this
// is a library capability for consumers to opt into, not something the tutorials themselves use.
include(":codegen")
include(":codegen-processor")
// Sample apps live in their own Gradle projects, outside this one: an Android app needs AGP and a
// Kotlin version old enough to pair with it, which dragged this whole build down to an old Gradle
// and caused repeated tooling breakage when nested here. They reach this library through a
// composite build (`includeBuild(...)` in their own settings.gradle.kts, see the README) —
// this project doesn't know they exist.
