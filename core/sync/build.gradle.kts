plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            api(projects.core.merge)
            api(projects.core.model)
            api(projects.core.storage)
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

// core:storage's database needs SQLite, whose Android artifact has it only for device ABIs. The
// same tests run on the JVM target and on linuxX64 (see core/storage/build.gradle.kts).
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
