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

// detekt registers one task per compilation. These run with type resolution, which enables rules
// that the per-source-set tasks and the plain `detekt` task skip, so `check` runs them instead.
// (The plain task only looks at src/main and src/test, which multiplatform modules don't have.)
// The names are spelled out so that a rename in a detekt release fails the build with "task not
// found" instead of silently dropping detekt from `check`. They assume a jotter.kmp-library
// module; modules with other targets will need their own list.
tasks.named("check") {
    dependsOn("detektMainJvm", "detektTestJvm", "detektMainAndroid", "detektHostTestAndroid")
}
