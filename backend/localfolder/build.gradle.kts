import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm

plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    id("jotter.android-device-test")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // The JVM and Android share one java.nio implementation of Platform.kt.
            group("javaNio") {
                withCompilations { it.platformType in setOf(jvm, androidJvm) }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            // Path is in the constructor of LocalFolderBlobStore.
            api(libs.okio)
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(projects.backend.api.contract)
        }
    }
}

// An app on Android has no hard links (AndroidStorageTest), so the device skips the tests that
// need them. Without links, a store without the lock cannot write at all.
deviceTest {
    runnerArguments["notAnnotation"] =
        "io.github.sameerkrmishra.jotter.backend.localfolder.NeedsHardLinks"
}
