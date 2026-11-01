// Compose on top of a module's Kotlin plugin: add it next to jotter.kmp-base (app/shared-ui) or a
// Kotlin/JVM application (app/desktop). It adds no libraries; each module names what it uses.

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
