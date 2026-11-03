plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            implementation(libs.ktor.client.core)
            // One engine on every target: the JVM, Android and linuxX64 (ADR-0006).
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Raw sockets, for a fake hub that misbehaves in ways a real server cannot.
            implementation(libs.ktor.network)
        }
    }
}
