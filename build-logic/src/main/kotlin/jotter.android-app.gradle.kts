// An Android application (app/android), with the project's compiler and lint rules. AGP 9 compiles
// its Kotlin itself, so no Kotlin plugin is applied. It builds for Android only, so ADR-0006's
// linuxX64 rule does not apply to it; code that another app could share belongs in a library.
// Apply jotter.quality after this plugin: it reads which plugin is applied to choose what Kover
// measures.

plugins {
    id("com.android.application")
}

// The same lint checks as jotter.kmp-base leaves out: their result changes with upstream releases.
val onlineVersionChecks =
    setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")

android {
    // AndroidX Compose 1.12 and Activity 1.13 require it (checkAarMetadata). The compile SDK only
    // says which APIs are visible; minSdk and targetSdk say where the app runs.
    compileSdk = 37

    defaultConfig {
        // Android 8.0, as for the libraries (jotter.kmp-base).
        minSdk = 26
        // Android 16 (PLAN.md §10, M5).
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        disable += onlineVersionChecks
    }
}

// detekt's type-resolved tasks (see jotter.kmp-base): detektMain covers every variant's main code,
// detektTest the host tests and the device tests. Spelled out for the same reason.
tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }
}
