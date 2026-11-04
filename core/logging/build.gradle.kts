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
            // The JVM and Android share the stack frames, the SLF4J provider and the
            // java.util.logging bridge (docs/adr/0020-local-logging.md).
            group("java") {
                withCompilations { it.platformType in setOf(jvm, androidJvm) }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The file sink writes through okio, which builds for linuxX64 (ADR-0006).
            api(libs.okio)
            implementation(libs.atomicfu)
        }
        getByName("javaMain").dependencies {
            // Ktor logs through SLF4J on the JVM and Android. This module is its provider, so
            // those lines reach the log file instead of a "no providers" warning on stderr.
            implementation(libs.slf4j.api)
        }
    }
}
