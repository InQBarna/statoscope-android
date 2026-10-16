plugins {
    kotlin("jvm")
}

group = "statoscope"
version = "3.0.0"

repositories {
    mavenCentral()
}

// Same toolchain rationale as the root project's build.gradle.kts.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // The library this testing DSL is built on top of — ports Swift Statoscope's
    // `StatoscopeTesting` SPM target depending on `Statoscope`. `api`, not `implementation`:
    // TestPlan's public signatures (ScopeConfig, InjectionKey, Effect) come straight from
    // statoscope.core, so a consumer of :testing needs those types visible too.
    api(project(":"))

    // TestPlan.thenEquals/thenNoEffects call kotlin.test.assertEquals/assertTrue directly —
    // `api`, not `implementation`, so any module depending on :testing also gets the JUnit5
    // adapter transitively rather than needing to redeclare it.
    api(kotlin("test"))

    // TestPlanLeakDetectionExtension implements JUnit5's AfterEachCallback — main-source
    // dependency (not testImplementation) because this extension class itself ships as part of
    // the :testing artifact's own compiled output, not this module's own test suite.
    implementation("org.junit.jupiter:junit-jupiter-api:5.10.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
