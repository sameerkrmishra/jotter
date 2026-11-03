plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    // The shared tests also run on Android devices: connectedAndroidDeviceTest. See README.md.
    id("jotter.android-device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // SqlDriver and SqlSchema appear in this module's public API.
            api(libs.sqldelight.runtime)
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
            implementation(libs.sqldelight.coroutines.extensions)
        }
    }
}

// sqlite-bundled's Android artifact ships SQLite only for device ABIs, so on the host JVM the
// Android variant fails with "UnsatisfiedLinkError: no sqliteJni". The same tests run on the JVM
// target (with SQLite for the host), on linuxX64 and, as device tests, on Android.
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
