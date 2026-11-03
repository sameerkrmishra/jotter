plugins {
    // Not jotter.kmp-library: Compose has no linuxX64 target (ADR-0010).
    id("jotter.kmp-base")
    id("jotter.compose")
    id("jotter.quality")
}

kotlin {
    android {
        // AndroidX Compose 1.12 requires it (checkAndroidMainAarMetadata). The compile SDK only
        // says which APIs are visible; minSdk is still 26.
        compileSdk = 37
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.storage)
            api(projects.core.sync)
            api(projects.app.sync)
            api(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.markdown)
        }
        jvmTest.dependencies {
            implementation(projects.backend.api.contract)
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.compose.desktop.linux.x64)
            implementation(libs.compose.ui.test)
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The JVM tests cover this module, and core:storage's SQLite has no Android variant for the host.
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
