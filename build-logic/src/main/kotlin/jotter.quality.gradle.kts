// Static analysis and coverage instrumentation shared by every module.
// The coverage threshold itself is enforced once, on the aggregated report (jotter.root).

import dev.detekt.gradle.extensions.FailOnSeverity

plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
}

// ktlint reads its code style and line length from the root .editorconfig.

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // Any finding fails the build, whatever its severity.
    failOnSeverity = FailOnSeverity.Info
}

// `check` runs only the plain `detekt` task by default, which has no type resolution. The
// type-resolved tasks depend on the module's targets: jotter.kmp-library wires its own, and a
// plain Kotlin/JVM module (such as a JVM CLI) gets its two here. Spelled out so that a rename in
// a detekt release fails the build instead of silently dropping detekt from `check`.
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    tasks.named("check") {
        dependsOn("detektMain", "detektTest")
    }
}
