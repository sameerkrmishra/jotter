// Runs a multiplatform module's shared tests (commonTest) on an Android device or emulator, as
// an instrumented test APK: ./gradlew :<module>:connectedAndroidDeviceTest. Apply it after
// jotter.kmp-library or jotter.kmp-base. Device-only tests go in src/androidDeviceTest. CI runs
// them on API 26 and 36 emulators in the `android-device` job, not in `check`, which has no
// emulator.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        packaging {
            // Licence files that both of JNA's jars (from Kotest) carry, which would clash in the
            // test APK.
            resources.excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
        }
        // sourceSetTreeName "test": androidDeviceTest depends on commonTest, so the shared tests
        // run on the device.
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            // This block runs at once, before the module's build script; AGP reads the map later.
            val extension = DeviceTestExtension(instrumentationRunnerArguments)
            project.extensions.add("deviceTest", extension)
        }
    }

    sourceSets.named("androidDeviceTest").configure {
        dependencies {
            implementation(libs.findLibrary("androidx-test-runner").get())
        }
    }
}

// `check` analyses the device tests with type resolution, as it does the other test source sets.
// It does not build them: the `android-device` CI job does, on every pull request.
tasks.named("check") {
    dependsOn("detektDeviceTestAndroid")
}
