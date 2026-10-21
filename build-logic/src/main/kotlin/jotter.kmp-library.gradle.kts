// A Kotlin Multiplatform library that builds for the JVM (Linux desktop, CLI, server) and Android.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Android Lint for the Android and JVM targets. Adds `lint` to `check`.
    id("com.android.lint")
}

// Lint options for the JVM target. The Android target has its own block below.
lint {
    warningsAsErrors = true
    abortOnError = true
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }

    jvm()

    android {
        // The namespace follows the Gradle path, e.g. :core:model -> ...jotter.core.model.
        namespace = "io.github.sameerkrmishra.jotter" + project.path.replace(':', '.')
        compileSdk = 36
        // Android 8.0. Covers nearly all devices in use and gives us java.time without desugaring.
        minSdk = 26

        withHostTest {}

        // Same options as the top-level lint block, which does not reach the Android target.
        lint {
            warningsAsErrors = true
            abortOnError = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
