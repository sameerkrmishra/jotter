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
    reports {
        filters {
            excludes {
                // Generated code is not ours to test. The storage module MUST configure
                // SQLDelight to generate into a package matching this pattern
                // (e.g. ...core.storage.db), and hand-written code must stay out of `db` packages.
                classes("*.db.*")
                // Thin platform entry points (Activity, `main()`) are covered by manual and UI
                // testing instead. Name them here as they are added, e.g. "*.MainKt".
            }
        }
        verify {
            rule {
                minBound(95, CoverageUnit.LINE)
                minBound(95, CoverageUnit.BRANCH)
            }
        }
    }
}

// The root build.gradle.kts and settings.gradle.kts are linted by default. Add the convention
// plugins and build scripts of build-logic, which is a separate build without ktlint.
ktlint {
    kotlinScriptAdditionalPaths {
        include(fileTree("build-logic") { include("*.kts", "src/**/*.kts") })
    }
}
