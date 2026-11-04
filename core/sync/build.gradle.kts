plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    id("jotter.android-device-test")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        all {
            languageSettings.optIn("io.github.sameerkrmishra.jotter.core.storage.SyncStoreApi")
        }
        commonMain.dependencies {
            api(projects.backend.api)
            api(projects.core.merge)
            api(projects.core.model)
            api(projects.core.storage)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // SHA-256 and gzip in common code (ADR-0006).
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(projects.backend.api.contract)
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio)
        }
    }
}

// The simulator reads its seed range from these. As task inputs, a new range is a new run, and the
// result of another range is never taken from the cache. See scripts/simulate.sh.
tasks.withType<AbstractTestTask>().configureEach {
    listOf("JOTTER_SIM_START", "JOTTER_SIM_CASES").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

// core:storage's database needs SQLite, whose Android artifact has it only for device ABIs. The
// same tests run on the JVM target and on linuxX64 (see core/storage/build.gradle.kts).
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
