plugins {
    kotlin("jvm")
}

group = "statoscope"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Same toolchain rationale as the root project's build.gradle.kts.
kotlin {
    jvmToolchain(21)
}

// Deliberately no dependencies at all — @Reducer/@SubState/@OnChildAction are SOURCE-retention
// marker annotations (see each one's own doc), read by :codegen-processor at compile time and
// never present in a compiled .class file. A module applying them needs this on its normal
// compile classpath; it never needs :codegen-processor there too (that one's `ksp`-only).
