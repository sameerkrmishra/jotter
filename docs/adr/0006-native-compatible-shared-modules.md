# 0006. Shared modules compile and test on Kotlin/Native

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

The Linux desktop app uses Compose Multiplatform, whose desktop target runs
only on the JVM ([spike 0002](../spikes/0002-desktop-packaging.md)). The CLI
([PLAN.md §3.2](../PLAN.md#32-module-layout-gradle)) is planned for the JVM
too, so both apps can share one build.

A CLI is started once per command, so its startup time matters more than its
throughput. A JVM process with Kotlin, Clikt and SQLite on the classpath may
take longer than feels instant. Our target is that `jotter add` finishes in
about 200 ms. We have not measured it yet; the CLI does not exist.

If the JVM is too slow, the fix is to build the CLI with Kotlin/Native as a
`linuxX64` executable, which starts in milliseconds. That is only cheap if all
shared modules (`core/*`, `backend/*`, `interop/*`) already build for Native.
Code that quietly uses `java.*` APIs, or a dependency without a Native variant,
would turn the switch into a rewrite. Nothing stops that from creeping in while
every target is a JVM or Android one.

## Decision

Every shared library module (the `jotter.kmp-library` convention plugin)
builds for a `linuxX64` target besides `jvm` and Android, and runs its tests
on it. `./gradlew check`, and therefore CI, compiles each module for
`linuxX64` and runs `linuxX64Test`. A module that only compiles for the JVM
fails the build.

No native binary is shipped. The apps (`app/desktop`, `app/cli`) stay on the
JVM for now.

In M2, once the CLI exists, we measure the wall-clock time of `jotter add` on
the JVM. If it is clearly above ~200 ms, and JVM tuning (class data sharing,
fewer dependencies at startup) does not fix it, we build the CLI for
`linuxX64` instead. That decision is recorded as its own ADR.

Platform-specific code goes behind `expect`/`actual` declarations or
interfaces in common code, with an implementation for each target.

The libraries that M1 needs were checked on 2026-10-26 with a throwaway
`linuxX64Test`:

- androidx `sqlite-bundled` 2.7.1 ([ADR-0002](0002-sqlite-via-sqldelight.md))
  publishes `sqlite-bundled-linuxx64`. `BundledSQLiteDriver` opened a temporary
  database file in WAL mode, created an FTS5 table and matched a row. It
  reports SQLite 3.50.1, the same build as on the JVM and Android. SQLite is
  linked statically into the test executable, and the `-wal` and `-shm` files
  were gone after close.
- SQLDelight 2.4.1 publishes `runtime-linuxx64`, which our own `SqlDriver`
  adapter needs. It resolved, compiled and linked.
- okio 3.18.2 publishes `okio-linuxx64`, so SHA-256 (`ByteString.sha256()`)
  and file access can live in common code. Verified against the known hash
  of `"abc"`.

## Consequences

### Positive

- A native CLI stays a packaging decision instead of a port.
- JVM-only APIs in shared code are caught on the pull request that adds them.
- Tests run on a second runtime, which can expose platform-dependent
  behaviour (string handling, number formatting, time zones) early.

### Negative

- Builds and CI are slower: every module is compiled and linked by the
  Kotlin/Native compiler as well. CI downloads about 1 GB of Native toolchain
  (LLVM, sysroot) on a cold cache, so it caches `~/.konan/dependencies`.
- Platform pieces need `expect`/`actual` or an interface, with one more
  implementation to write and test.
- Kover cannot measure Kotlin/Native. Native tests prove that the code behaves the
  same on Native, but they do not count towards the 95% coverage gate. Code
  in Native-only source sets (`nativeMain`, `linuxX64Main`) is not covered by
  the gate at all and must stay thin.
- detekt has no type resolution for Native. Native-only source sets are
  analysed without it, so some rules do not run on them.
- Every library used in shared modules must publish a `linuxX64` variant.
  This rules out JVM-only libraries in shared code (for example xerial
  `sqlite-jdbc` or `java.security.MessageDigest`); they are fine in app
  modules.

## Alternatives considered

- **All JVM, without a Native guard:** the simplest build. But JVM-only code
  and dependencies would accumulate unnoticed, and a native CLI later would
  mean finding and replacing them all at once.
- **GraalVM `native-image` for the JVM CLI:** fast startup without leaving the
  JVM. But it needs reflection and resource configuration for Kotlin, Clikt
  and the SQLite JNI library, adds a separate toolchain to CI, and its builds
  are slow. It can still be tried in M2 if the Native route turns out harder.
- **A native GTK GUI instead of Compose Desktop:** would make the desktop app
  Native too, but means a second UI codebase next to Android's Compose UI,
  against the shared-UI goal ([PLAN.md §3.4](../PLAN.md#34-key-technology-choices)).
