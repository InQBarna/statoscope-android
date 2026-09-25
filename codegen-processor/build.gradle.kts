plugins {
    kotlin("jvm")
    // Self-testing: this module's own test sources use @Reducer/@SubState/@OnChildAction and
    // get processed by the very SymbolProcessor this module builds (see `kspTest` below) — the
    // standard way to test a KSP processor against real generated output without pulling in an
    // external compile-testing library of uncertain compatibility with a Kotlin version this
    // new (see settings.gradle.kts's own note on why 2.3.12 was picked empirically, not by
    // exact-version convention).
    id("com.google.devtools.ksp")
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
    // Pure KSP API — this module never depends on the root project. It validates/emits against
    // fully-qualified name strings (see SubScopeProcessor's own doc), never actual Kotlin types
    // from statoscope.core; a consumer applying `ksp(project(":codegen-processor"))` still needs
    // its OWN dependency on statoscope-kotlin for the generated code to compile against.
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    implementation(project(":codegen"))

    testImplementation(project(":codegen"))
    // Only the test sources need the real SubScope/ScopeConfig/Dependencies/EffectsState types
    // the generated code references — the processor itself (main sources) never does.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))
    kspTest(project(":codegen-processor"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
