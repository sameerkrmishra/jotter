plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
}

kotlin {
    android {
        // The shared tests (commonTest) also run on an Android device or emulator:
        // ./gradlew :core:sqlite:connectedAndroidDeviceTest. Not part of `check`, because CI has no
        // emulator. See README.md.
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

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
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
        }
    }
}

// sqlite-bundled's Android artifact ships SQLite only for device ABIs, so on the host JVM the
// Android variant fails with "UnsatisfiedLinkError: no sqliteJni". The same tests run on the JVM
// target (with SQLite for the host), on linuxX64 and, as device tests, on Android.
tasks.named { it == "testAndroidHostTest" }.configureEach {
    enabled = false
}
