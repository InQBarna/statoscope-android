import java.io.File

plugins {
    kotlin("jvm")
}

group = "statoscope"
version = "0.1.0"

repositories {
    mavenCentral()
}

// A real Gradle/Kotlin toolchain, not a manual sourceCompatibility/jvmTarget pin: this picks
// (or, via the foojay-resolver-convention plugin in settings.gradle.kts, auto-downloads) a
// JDK 21 to compile, test, and run this module with — 21 because that's what the Android
// Gradle Plugin needs once this project actually grows an Android target, and because it's
// what Android Studio's own bundled JBR ships, so no IDE-vs-CLI JVM mismatch. Crucially, the
// JVM running Gradle itself (the daemon — whatever `./gradlew` or Android Studio's own "Gradle
// JVM" setting launches, e.g. this machine's only installed system JDK, 26) no longer needs to
// match: toolchains resolve a separate, correct JVM per module for actual compilation/execution.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // For describeObject's reflective state/event pretty-printer (StatoscopeLogger).
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    // The testing DSL (given/TestPlan) — lives in its own module (see testing/build.gradle.kts'
    // doc comment) rather than this project's own src/test, so both this project's tutorial
    // examples and any external consumer (e.g. examples/todolist) depend on it the same way.
    testImplementation(project(":testing"))
    // For deterministic effect-execution tests: a TestScope/StandardTestDispatcher passed as
    // createScope's effectScope, driven with runTest { ... advanceUntilIdle() }, instead of a
    // flaky Thread.sleep + poll.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// ---------------------------------------------------------------------------
// extractDocSnippets
//
// Re-implements Scripts/extract-doc-snippets.swift's algorithm (see the Swift
// Statoscope sibling project) for this project's docs pipeline. Scans example
// source files for `@extract:begin <name>` / `@extract:end <name>` marker
// comments and writes the lines collected between each matched pair to
// docs/_generated/<name>.kt, dedented to the minimum indentation among them.
//
// Supports multiple simultaneously-open markers per file, and a marker being
// closed and reopened later in the same file (its collected lines simply keep
// growing) — the same nesting technique the Swift source files use to reveal
// progressively more of one growing declaration across several tutorial steps.
// ---------------------------------------------------------------------------

data class ExtractedSnippet(val name: String, val lines: MutableList<String> = mutableListOf())

fun extractSnippets(sourceFile: File): List<ExtractedSnippet> {
    val beginRegex = Regex("""@extract:begin\s+(\S+)""")
    val endRegex = Regex("""@extract:end\s+(\S+)""")
    val snippets = linkedMapOf<String, ExtractedSnippet>()
    val active = mutableSetOf<String>()

    sourceFile.readLines().forEach { line ->
        val endMatches = endRegex.findAll(line).map { it.groupValues[1] }.toList()
        endMatches.forEach { name -> active.remove(name) }

        val beginMatches = beginRegex.findAll(line).map { it.groupValues[1] }.toList()
        beginMatches.forEach { name ->
            snippets.getOrPut(name) { ExtractedSnippet(name) }
            active.add(name)
        }

        val isMarkerOnlyLine = line.trim().startsWith("//") &&
            (line.contains("@extract:begin") || line.contains("@extract:end"))
        if (!isMarkerOnlyLine) {
            active.forEach { name -> snippets[name]!!.lines.add(line) }
        }
    }
    return snippets.values.toList()
}

fun dedent(lines: List<String>): String {
    val trimmed = lines.toMutableList()
    while (trimmed.isNotEmpty() && trimmed.first().isBlank()) trimmed.removeAt(0)
    while (trimmed.isNotEmpty() && trimmed.last().isBlank()) trimmed.removeAt(trimmed.size - 1)
    if (trimmed.isEmpty()) return ""
    val minIndent = trimmed.filter { it.isNotBlank() }
        .minOf { it.length - it.trimStart().length }
    return trimmed.joinToString("\n") { line ->
        if (line.isBlank()) "" else line.drop(minIndent)
    } + "\n"
}

val extractDocSnippets by tasks.registering {
    group = "documentation"
    description = "Extracts @extract-marked snippets from example sources into docs/_generated/"

    val sourceFiles = files(
        "src/test/kotlin/statoscope/examples/tutorial01/CounterExample.kt",
        "src/test/kotlin/statoscope/examples/tutorial02/CloudCounterExample.kt",
        "src/test/kotlin/statoscope/examples/tutorial03/MiddlewareExample.kt",
        "src/test/kotlin/statoscope/examples/tutorial04/InjectionExample.kt"
    )
    val outputDir = layout.projectDirectory.dir("docs/_generated")

    inputs.files(sourceFiles)
    outputs.dir(outputDir)

    doLast {
        val outDir = outputDir.asFile
        outDir.mkdirs()
        var total = 0
        sourceFiles.forEach { sourceFile ->
            extractSnippets(sourceFile).forEach { snippet ->
                val outFile = outDir.resolve("${snippet.name}.kt")
                outFile.writeText(dedent(snippet.lines))
                println("Generated: ${outFile.relativeTo(projectDir)}")
                total++
            }
        }
        println("Extracted $total snippet(s).")
    }
}
