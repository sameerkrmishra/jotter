// A Kotlin Multiplatform library that builds for the JVM (Linux desktop, CLI, server), Android
// and linuxX64. Nothing ships for linuxX64 yet: the target keeps shared code free of JVM-only APIs
// so the CLI can become a native binary later (docs/adr/0006-native-compatible-shared-modules.md).

plugins {
    id("jotter.kmp-base")
}

kotlin {
    // Its tests (linuxX64Test) run in `check`. Kover cannot measure native code, so they prove
    // that the code works on Native but do not count towards the coverage gate.
    linuxX64()
}

// detekt has no type-resolved task for Kotlin/Native, so native-only source sets get their
// per-source-set task. jotter.kmp-base wires the other tasks. Spelled out for the same reason.
tasks.named("check") {
    dependsOn(
        "detektNativeMainSourceSet",
        "detektNativeTestSourceSet",
        "detektLinuxMainSourceSet",
        "detektLinuxTestSourceSet",
        "detektLinuxX64MainSourceSet",
        "detektLinuxX64TestSourceSet",
    )
}
