plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // NoteService and NoteId appear in KeepImporter's and the report's API.
            api(projects.core.storage)
            implementation(libs.kotlinx.serialization.json)
            // SHA-1 for the note id.
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.okio)
        }
    }
}

// As in core:storage: sqlite-bundled's Android artifact ships SQLite only for device ABIs, so the
// importer's store cannot open on the host JVM. The same tests run on the JVM target and linuxX64.
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
