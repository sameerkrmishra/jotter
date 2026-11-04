import java.time.Duration

// JVM only: the hub runs inside the desktop app's JVM, on blocking JDK sockets whose descriptors,
// threads and memory it can bound and close for certain (docs/adr/0015-pc-hub-peer-protocol.md).
plugins {
    id("jotter.kotlin-jvm")
    id("jotter.quality")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The protocol, shared with the client: paths, error codes and limits (ADR-0015).
    api(projects.backend.peer)
    // The hub's own store is a local folder on the PC.
    api(projects.backend.localfolder)
    api(libs.okio)
    api(libs.kotlinx.coroutines.core)
    // Ktor's HTTP/1.1 parser for request heads. The sockets are the JDK's.
    implementation(libs.ktor.http.cio)
    implementation(projects.core.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.atomicfu)

    // The end-to-end tests: the peer client against this server, over loopback.
    testImplementation(projects.backend.api.contract)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The stress tests open many sockets and hold large answers. A bounded heap and a hard timeout
// keep a regression from wedging the machine instead of failing.
tasks.test {
    maxHeapSize = "768m"
    timeout = Duration.ofMinutes(20)
}
