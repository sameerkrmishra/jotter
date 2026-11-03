// Kotlin/JVM with the project's compiler rules, for an application. It builds for the JVM only, so
// it may use JVM-only libraries. Code that other apps could share belongs in a jotter.kmp-library
// module (docs/adr/0006-native-compatible-shared-modules.md). Apply jotter.jvm-app for a plain
// application; app/desktop adds jotter.compose instead, whose own `run` task clashes with Gradle's
// `application` plugin.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
