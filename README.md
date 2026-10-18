# Statoscope for Kotlin

Unidirectional state management with scopes, reducers and effects: the Kotlin port of InQBarna's Swift [Statoscope](https://github.com/InQBarna/statoscope).
A `@Reducer` object holds a feature's state, events and `update`; reducers nest as scopes (`@SubState`), children read what they need from
their ancestors (`@ParentState`), and effects are enqueued by `update` instead of run in it, so a whole scope tree can be tested with a plain
`TestPlan`.

## Modules

| Artifact | What it is |
|---|---|
| `statoscope-kotlin` | the core: `createScope`, `ScopeConfig`, effects, dependencies |
| `statoscope-android` | `createAndroidScope` (main-thread guaranteed) and Compose helpers |
| `statoscope-codegen` | the annotations: `@Reducer`, `@SubState`, `@ParentState`, `@OnDescendantAction`, `@ReducerInjected` |
| `statoscope-codegen-processor` | the KSP processor that generates each reducer's `config` and its sub scopes |
| `statoscope-testing` | the `TestPlan` DSL (a JUnit 5 extension detects a plan that was never run) |

## Install

Published through [JitPack](https://jitpack.io) from the git tags (Maven Central later: the coordinates will be
`com.inqbarna.statoscope:statoscope-*`).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.InQBarna.statoscope-android:statoscope-kotlin:3.1.0")
    implementation("com.github.InQBarna.statoscope-android:statoscope-android:3.1.0")
    implementation("com.github.InQBarna.statoscope-android:statoscope-codegen:3.1.0")
    ksp("com.github.InQBarna.statoscope-android:statoscope-codegen-processor:3.1.0")
    testImplementation("com.github.InQBarna.statoscope-android:statoscope-testing:3.1.0")
}
```

The KSP plugin has to be applied in the module that declares the reducers.

## Work on the library next to an app

An app can include this repository as a composite build and substitute the artifacts with the local source:

```kotlin
// the app's settings.gradle.kts
includeBuild("../statoscope-android") {
    dependencySubstitution {
        substitute(module("com.github.InQBarna.statoscope-android:statoscope-kotlin")).using(project(":"))
        substitute(module("com.github.InQBarna.statoscope-android:statoscope-android")).using(project(":android"))
        substitute(module("com.github.InQBarna.statoscope-android:statoscope-codegen")).using(project(":codegen"))
        substitute(module("com.github.InQBarna.statoscope-android:statoscope-codegen-processor")).using(project(":codegen-processor"))
        substitute(module("com.github.InQBarna.statoscope-android:statoscope-testing")).using(project(":testing"))
    }
}
```

## Build and release

```bash
./gradlew test                      # the tests of every module
./gradlew publishToMavenLocal       # all modules to ~/.m2 as com.inqbarna.statoscope:statoscope-*:<VERSION>
```

The group and the version live in `gradle.properties` (`GROUP`, `VERSION`). To release: bump `VERSION`, add the entry to
[CHANGELOG.md](CHANGELOG.md), commit, tag it with the same number (`git tag 3.1.0`) and push the tag; JitPack builds it on the first request.

## License

Apache License 2.0, see [LICENSE](LICENSE).
