plugins {
    // Before jotter.quality, which reads it.
    id("jotter.android-app")
    id("jotter.quality")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sameerkrmishra.jotter.app.android"

    defaultConfig {
        applicationId = "io.github.sameerkrmishra.jotter"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The end-to-end test needs the PC's half on the host: scripts/hub-e2e.sh runs it alone,
        // with `am instrument`. Left out of Gradle's runs, so that CI counts no skipped test.
        testInstrumentationRunnerArguments["notClass"] =
            "io.github.sameerkrmishra.jotter.app.android.HubEndToEndTest"
    }

    buildTypes {
        release {
            // Not shrunk yet: F-Droid's reproducible build (another M5 task) decides on R8.
            isMinifyEnabled = false
        }
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
