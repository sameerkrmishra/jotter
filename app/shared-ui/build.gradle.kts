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
        jvmMain.dependencies {
            // The PC hub runs inside the desktop app's JVM (ADR-0015). Not on Android.
            implementation(projects.server)
        }
        jvmTest.dependencies {
            // DatabaseUpgradeException, for LogRedactionTest.
            implementation(projects.core.sqlite)
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

// The live test in the package `live` syncs two apps through a real WebDAV server that
// backend/webdav/scripts/live-test.sh starts. It runs only when that script sets
// JOTTER_WEBDAV_LIVE, and then alone. The URL is a task input, so a result is never taken from the
// cache for another server.
val webDavLive = providers.environmentVariable("JOTTER_WEBDAV_LIVE").orNull == "1"

tasks.withType<AbstractTestTask>().configureEach {
    listOf("JOTTER_WEBDAV_LIVE", "JOTTER_WEBDAV_LIGHTTPD_URL").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    filter.run {
        isFailOnNoMatchingTests = false
        if (webDavLive) {
            includeTestsMatching("*.sharedui.live.*")
        } else {
            excludeTestsMatching("*.sharedui.live.*")
        }
    }
}
