// A Kotlin Multiplatform library that builds for the JVM (Linux desktop, CLI, server), Android
// and linuxX64. Nothing ships for linuxX64 yet: the target keeps shared code free of JVM-only APIs
// so the CLI can become a native binary later (docs/adr/0006-native-compatible-shared-modules.md).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Android Lint for the Android and JVM targets. Adds `lint` to `check`.
    id("com.android.lint")
}

// Lint checks that compare our dependency versions with the latest releases online. Their result
// changes whenever upstream ships a release, so the same commit would pass today and fail
// tomorrow. Dependency updates are a deliberate change, not a lint error.
val onlineVersionChecks =
    setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")

// Lint options for the JVM target. The Android target has its own block below.
lint {
    warningsAsErrors = true
    abortOnError = true
    disable += onlineVersionChecks
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }

    jvm()
    // Its tests (linuxX64Test) run in `check`. Kover cannot measure native code, so they prove
    // that the code works on Native but do not count towards the coverage gate.
    linuxX64()

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
            disable += onlineVersionChecks
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// detekt (applied by jotter.quality) registers one task per JVM and Android compilation. These run
// with type resolution, which enables rules that the per-source-set tasks and the plain `detekt`
// task skip, so `check` runs them. They also cover commonMain and commonTest. detekt has no
// type-resolved task for Kotlin/Native, so native-only source sets get their per-source-set task.
// The names are spelled out so that a rename in a detekt release fails the build with "task not
// found" instead of silently dropping detekt from `check`.
tasks.named("check") {
    dependsOn("detektMainJvm", "detektTestJvm", "detektMainAndroid", "detektHostTestAndroid")
    dependsOn(
        "detektNativeMainSourceSet",
        "detektNativeTestSourceSet",
        "detektLinuxMainSourceSet",
        "detektLinuxTestSourceSet",
        "detektLinuxX64MainSourceSet",
        "detektLinuxX64TestSourceSet",
    )
}
