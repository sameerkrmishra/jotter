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
            // Resolving the hub's name, to check the address before the token goes there.
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
