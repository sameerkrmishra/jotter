// A Kotlin Multiplatform library that builds for the JVM (Linux desktop, CLI, server) and Android.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }

    jvm()

    android {
        // Each module's namespace follows its Gradle path, e.g. :core:model -> ...jotter.core.model.
        namespace = "io.github.sameerkrmishra.jotter" + project.path.replace(':', '.')
        compileSdk = 36
        // Android 8.0. Covers nearly all devices in use and gives us java.time without desugaring.
        minSdk = 26

        withHostTest {}
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
