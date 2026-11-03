plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The protocol, shared with the client: paths, error codes and limits (ADR-0015).
            api(projects.backend.peer)
            // The hub's own store is a local folder on the PC.
            api(projects.backend.localfolder)
            api(libs.okio)
            api(libs.kotlinx.coroutines.core)
            // Ktor's sockets and HTTP/1.1 parser, under an accept loop of our own that caps
            // connections, header sizes and time (ADR-0015).
            implementation(libs.ktor.network)
            implementation(libs.ktor.http.cio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            // The end-to-end tests: the peer client against this server, over loopback.
            implementation(projects.backend.api.contract)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
