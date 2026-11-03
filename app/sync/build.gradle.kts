plugins {
    // A library, not Compose: the CLI needs this too, so it builds for linuxX64 (ADR-0006, ADR-0010).
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

// Not "sync", like core:sync's: the CLI's distribution puts both jars in one directory.
base {
    archivesName = "app-sync"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            api(projects.core.storage)
            api(projects.core.sync)
            implementation(projects.backend.localfolder)
            implementation(projects.backend.webdav)
            implementation(projects.backend.peer)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(projects.backend.api.contract)
            // To make the database fail a write.
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // A real PC hub on loopback, for the peer backend's tests. The hub is JVM only.
            implementation(projects.server)
        }
    }
}

// core:storage's database needs SQLite, whose Android artifact has it only for device ABIs. The
// same tests run on the JVM target and on linuxX64 (see core/storage/build.gradle.kts).
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
