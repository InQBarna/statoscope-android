// Shared by every published module: sources and javadoc jars, the POM (name, license, scm), and the Maven publication.
// Published as com.inqbarna.statoscope:statoscope-kotlin (the core, the root project) and statoscope-android, statoscope-codegen,
// statoscope-codegen-processor and statoscope-testing. JitPack builds them from a tag with `publishToMavenLocal` (jitpack.yml);
// Maven Central would add signing here (see README.md).
apply(plugin = "maven-publish")

configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

val publishedArtifactId = if (project == rootProject) "statoscope-kotlin" else "statoscope-${project.name}"
val publishedDescription = when (project.name) {
    "android" -> "Statoscope for Android: createAndroidScope, a main-thread guaranteed scope, and Compose integration"
    "codegen" -> "Statoscope code generation: the annotations (@Reducer, @SubState, @ParentState, ...)"
    "codegen-processor" -> "Statoscope KSP processor: generates the scope configs and the sub scopes of the annotated reducers"
    "testing" -> "Statoscope test plans: a DSL to test reducers and whole scope trees"
    else -> "Statoscope: unidirectional state management with scopes, reducers and effects (the Kotlin port of InQBarna's Swift library)"
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = publishedArtifactId
            pom {
                name.set(publishedArtifactId)
                description.set(publishedDescription)
                url.set("https://github.com/InQBarna/statoscope-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("inqbarna")
                        name.set("InQBarna")
                        url.set("https://inqbarna.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/InQBarna/statoscope-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/InQBarna/statoscope-android.git")
                    url.set("https://github.com/InQBarna/statoscope-android")
                }
            }
        }
    }
}
