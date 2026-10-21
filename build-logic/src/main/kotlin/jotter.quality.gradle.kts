// Static analysis and coverage instrumentation shared by every module.
// The coverage threshold itself is enforced once, on the aggregated report (jotter.coverage).

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
    // The default only covers src/main and src/test. This covers every source set (commonMain,
    // jvmTest, ...) in one `detekt` task, which is the one `check` runs.
    source.setFrom(layout.projectDirectory.dir("src"))
    // Any finding fails the build, whatever its severity.
    failOnSeverity = FailOnSeverity.Info
}
