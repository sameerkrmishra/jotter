// A Kotlin/JVM application, such as the CLI, with a start script and an `installDist` task.
// It builds for the JVM only, so it may use JVM-only libraries. Code that other apps could share
// belongs in a jotter.kmp-library module (docs/adr/0006-native-compatible-shared-modules.md).

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
