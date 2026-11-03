// Applied to the root project only. Enforces the coverage gate from docs/PLAN.md §8 on the merged
// Kover data of every module listed in the root build's `kover(...)` dependencies, and lints the
// build scripts that live outside any module.

import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    // Gives the root project a `check` task, so `./gradlew check` also runs the tasks below.
    base
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

kover {
    // JaCoCo rather than Kover's default engine because it filters the branches that the Compose
    // compiler generates (such as a composable's skip checks). No test can reach those, and with
    // the default engine they alone put app/shared-ui's branch coverage at 73%.
    useJacoco("0.8.14")
    currentProject {
        // Collects the variant of the same name from each module; see jotter.quality.
        createVariant("coverage") {}
    }
    reports {
        filters {
            excludes {
                // Generated code is not ours to test. The storage module MUST configure
                // SQLDelight to generate into a package matching this pattern
                // (e.g. ...core.storage.db), and hand-written code must stay out of `db` packages.
                classes("*.db.*")
                // The serialization plugin adds a constructor and write$Self to @Serializable
                // classes, with one branch per field and per default. Such classes go in a `dto`
                // package of their module, named here, and stay plain data (CONTRIBUTING.md).
                classes("*.keepimport.dto.*")
                classes("*.sync.dto.*")
                classes("*.peer.dto.*")
                classes("*.server.dto.*")
                // Thin platform entry points (Activity, `main()`) are covered by manual and UI
                // testing instead. Name them here as they are added, e.g. "*.MainKt".
                classes("*.app.cli.MainKt")
                // The lambdas of `main()` are classes of their own.
                classes("*.desktop.MainKt", "*.desktop.MainKt$*")
            }
        }
        variant("coverage") {
            verify {
                rule {
                    minBound(95, CoverageUnit.LINE)
                    minBound(95, CoverageUnit.BRANCH)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerifyCoverage")
}

// The root build.gradle.kts and settings.gradle.kts are linted by default. Add the convention
// plugins and build scripts of build-logic, which is a separate build without ktlint.
ktlint {
    kotlinScriptAdditionalPaths {
        include(fileTree("build-logic") { include("*.kts", "src/**/*.kts") })
    }
}
