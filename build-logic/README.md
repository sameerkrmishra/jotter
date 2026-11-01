# build-logic

Convention plugins shared by all Jotter modules. Each is a precompiled script
plugin in `src/main/kotlin/`, and its id is the file name. Plugin versions come
from `gradle/libs.versions.toml`.

| Plugin | Apply to | What it does |
|---|---|---|
| `jotter.kmp-library` | Every multiplatform library module | `jotter.kmp-base` plus a `linuxX64()` target, compiled and tested only, never shipped, to keep shared code free of JVM-only APIs ([ADR-0006](../docs/adr/0006-native-compatible-shared-modules.md)). Adds detekt's Native tasks to `check`. |
| `jotter.kmp-base` | Only a module that cannot build for `linuxX64`, which is `app/shared-ui` ([ADR-0010](../docs/adr/0010-compose-ui-outside-linuxx64-rule.md)) | Kotlin Multiplatform with `jvm()` and Android (`com.android.kotlin.multiplatform.library`) targets. JDK 21 toolchain, `explicitApi()`, warnings as errors. Android: compileSdk 36, minSdk 26, namespace derived from the Gradle path (without hyphens), host tests enabled. Android Lint on the JVM and Android targets, warnings as errors. Adds `kotlin.test` to `commonTest`. Adds detekt's per-target tasks to `check`. |
| `jotter.compose` | A module with Compose, next to its Kotlin plugin | The Compose compiler plugin (versioned with Kotlin) and `org.jetbrains.compose`. Adds no libraries. |
| `jotter.quality` | Every module | ktlint (style from the root `.editorconfig`), detekt (`config/detekt/detekt.yml` on top of the defaults; any finding fails the build) and Kover instrumentation with a `coverage` variant of the JVM classes. For a plain Kotlin/JVM module it also adds the type-resolved detekt tasks to `check`; `jotter.kmp-base` does that for multiplatform modules. Any other module (a pure Android app, or one that applies `kotlin.multiplatform` directly) only gets the plain `detekt` task, without type resolution, until its own type-resolved tasks are added to `check`. |
| `jotter.kotlin-jvm` | A Kotlin/JVM application | Kotlin/JVM, JDK 21 toolchain, `explicitApi()`, warnings as errors. Adds `kotlin.test` to the tests. `app/desktop` applies it with `jotter.compose`, whose own `run` task rules out Gradle's `application` plugin. Apply `jotter.quality` next to it. |
| `jotter.jvm-app` | A Kotlin/JVM application without Compose, such as `app/cli` | `jotter.kotlin-jvm` with Gradle's `application` plugin (`installDist`, start scripts). Its entry point goes in the Kover exclusions of `jotter.root`. |
| `jotter.root` | The root project only | Merges the `coverage` variants of the modules listed as `kover(...)` dependencies in the root `build.gradle.kts` and fails `check` below 95% line or branch coverage. Generated code is excluded here. Also runs ktlint on the root build scripts and on everything in `build-logic/`. |

A new library module needs only:

```kotlin
plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
}
```

plus `include(...)` in `settings.gradle.kts` and a `kover(...)` line in the root
`build.gradle.kts`.

## JDK

The build runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`) and compiles
with a JDK 21 toolchain. Any JDK can launch `./gradlew`. If no JDK 21 is
installed, Gradle downloads one through the foojay toolchain resolver. To
change the daemon JDK, run `./gradlew updateDaemonJvm --jvm-version=<n>`.

## Kotlin/Native

The first build that compiles for `linuxX64` downloads the Kotlin/Native
toolchain (about 1.8 GB) to `~/.konan`. `linuxX64Test` runs in `check`, but
Kover cannot measure native code, so the native tests do not count towards
the coverage gate. detekt has no type resolution for Native: native-only
source sets (`nativeMain`, `linuxMain`, `linuxX64Main` and their tests) are
analysed without it.

## Coverage

Kover runs with the JaCoCo engine. The Compose compiler adds branches to every
composable, such as its skip checks, that no test can reach. JaCoCo filters them;
Kover's default engine does not, and counted `app/shared-ui` at 73% branch
coverage with 99% of its lines covered.

Two things follow from that:

- JaCoCo matches a class to the test data by its bytes. The Android build of a
  shared class has the same name, and other bytes where it inlines the Android
  variant of a library. So the gate reads the `coverage` variant, the JVM classes
  only, with `koverVerifyCoverage` and `koverXmlReportCoverage`. The plain
  `koverXmlReport` reports such classes as untested.
- A view model passed to a composable should be `@Stable`, as ours are. For an
  unstable parameter the compiler generates more checks in each lambda that
  captures it, which JaCoCo does not filter and no test can reach.

## Known issues

`--warning-mode=fail` cannot be used yet: Kover 0.9.11 calls the deprecated
`Configuration.setVisible`, which Gradle 9.8 reports as a deprecation warning.
