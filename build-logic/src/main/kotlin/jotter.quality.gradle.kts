// Static analysis and coverage instrumentation shared by every module.
// The coverage threshold itself is enforced once, on the aggregated report (jotter.root).

import dev.detekt.gradle.Detekt
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

// detekt registers one task per compilation (detektMainJvm, detektTestJvm, detektMainAndroid,
// ...). These run with type resolution, which enables rules that the per-source-set tasks and the
// plain `detekt` task skip, so `check` runs them instead. The plain `detekt` task only looks at
// src/main and src/test, which multiplatform modules don't have, so it reports NO-SOURCE.
tasks.named("check") {
    dependsOn(
        tasks.withType<Detekt>().matching { !it.name.endsWith("SourceSet") && it.name != "detekt" },
    )
}
