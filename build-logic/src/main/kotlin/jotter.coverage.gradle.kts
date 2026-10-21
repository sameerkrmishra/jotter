// Applied to the root project only. Merges the Kover data of every module listed in the root
// build's `kover(...)` dependencies and enforces the coverage gate from docs/PLAN.md §8.

import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    // Gives the root project a `check` task, so `./gradlew check` also runs koverVerify here.
    base
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                // Generated code is not ours to test. By convention, SQLDelight is configured to
                // generate into a `db` package (e.g. ...core.storage.db), so keep hand-written
                // code out of packages named `db`.
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
