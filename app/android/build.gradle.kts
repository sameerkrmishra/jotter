plugins {
    // Before jotter.quality, which reads it.
    id("jotter.android-app")
    id("jotter.quality")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version, from gradle.properties only (ADR-0019): never the clock, the commit or the machine.
val releaseVersionName = providers.gradleProperty("jotter.versionName").get()
val releaseVersionCode = providers.gradleProperty("jotter.versionCode").get().toInt()
require(Regex("""\d+\.\d+\.\d+""").matches(releaseVersionName)) {
    "jotter.versionName must be MAJOR.MINOR.PATCH, not $releaseVersionName"
}
require(releaseVersionCode > 0) { "jotter.versionCode must be positive" }

// Release signing (ADR-0019). Only the maintainer's machine has a keystore: with JOTTER_KEYSTORE
// set, the release APK is signed with it, and its passwords come from the environment, never a
// file in the repository. Without it, as in CI and on F-Droid's build server, the release APK is
// unsigned.
val releaseKeystore = providers.environmentVariable("JOTTER_KEYSTORE").orNull

android {
    namespace = "io.github.sameerkrmishra.jotter.app.android"

    defaultConfig {
        applicationId = "io.github.sameerkrmishra.jotter"
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The end-to-end test needs the PC's half on the host: scripts/hub-e2e.sh runs it alone,
        // with `am instrument`. Left out of Gradle's runs, so that CI counts no skipped test.
        testInstrumentationRunnerArguments["notClass"] =
            "io.github.sameerkrmishra.jotter.app.android.HubEndToEndTest"
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = requiredEnvironment("JOTTER_KEYSTORE_PASSWORD")
                keyAlias = providers.environmentVariable("JOTTER_KEY_ALIAS").getOrElse("jotter")
                keyPassword =
                    providers.environmentVariable("JOTTER_KEY_PASSWORD").orNull ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk: R8 would need keep rules for Ktor's and kotlinx's reflection, and a
            // release build tested on devices, which CI's device tests (debug builds) are not.
            isMinifyEnabled = false
            // The APK depends on the sources only, not on whether they came with .git: F-Droid
            // builds from a clone, the reproducibility check from `git archive`.
            vcsInfo.include = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Google's dependency metadata block, encrypted with Google's key, is added to signed APKs.
    // F-Droid refuses it, as nobody else can read it, and it would differ from F-Droid's build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // Duplicated licence and metadata files of the Ktor and kotlinx jars.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation(projects.app.sharedUi)
    implementation(projects.app.sync)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    // The sealed secrets file. Its JSON is built by hand, so no serialization plugin.
    implementation(libs.kotlinx.serialization.json)
    // The hub client's engine, on sockets bound to Tailscale's network (ADR-0018).
    implementation(libs.ktor.client.okhttp)
    implementation(projects.backend.peer)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    // A real hub on loopback, for the OkHttp engine of the hub client.
    testImplementation(projects.server)

    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(projects.backend.api.contract)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

fun requiredEnvironment(name: String): String =
    requireNotNull(providers.environmentVariable(name).orNull) {
        "$name must be set when JOTTER_KEYSTORE is (docs/releasing.md)"
    }
