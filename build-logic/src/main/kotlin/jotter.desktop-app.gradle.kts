// SPIKE: a Compose Desktop application (Linux). Kotlin/JVM + Compose compiler + Compose plugin,
// with the same compiler strictness as jotter.kmp-library. Packaging is configured per module.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
