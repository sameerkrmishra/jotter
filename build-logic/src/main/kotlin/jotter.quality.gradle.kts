// Static analysis and coverage instrumentation shared by every module.
// The coverage threshold itself is enforced once, on the aggregated report (jotter.root).

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import java.io.File

plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
}

// Generated code (SQLDelight's, in core/storage) is in the build directory. It is not ours to
// format or analyse, so ktlint and detekt skip it. The trailing separator keeps a sibling such as
// `build-tools/` included. (Each block reads the path into a local: a
// lambda that refers to a script property cannot be stored in the configuration cache.)

// ktlint reads its code style and line length from the root .editorconfig.
ktlint {
    filter {
        // ktlintCheck, which `check` runs, honours this filter. ktlintFormat does not: it reports
        // the generated code it cannot fix.
        val generated =
            layout.buildDirectory.asFile
                .get()
                .path + File.separator
        exclude { element -> element.file.path.startsWith(generated) }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // Any finding fails the build, whatever its severity.
    failOnSeverity = FailOnSeverity.Info
}

tasks.withType<Detekt>().configureEach {
    val generated =
        layout.buildDirectory.asFile
            .get()
            .path + File.separator
    exclude { element -> element.file.path.startsWith(generated) }
}

// `check` runs only the plain `detekt` task by default, which has no type resolution. The
// type-resolved tasks depend on the module's targets: jotter.kmp-library wires its own, and a
// plain Kotlin/JVM module (such as a JVM CLI) gets its two here. Any other module (a pure Android
// app, or one applying kotlin.multiplatform directly, such as app/desktop) falls back to the plain
// task, so whoever adds one must add its type-resolved tasks to `check`. Spelled out so that a
// rename in a detekt release fails the build instead of silently dropping detekt from `check`.
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    tasks.named("check") {
        dependsOn("detektMain", "detektTest")
    }
}
