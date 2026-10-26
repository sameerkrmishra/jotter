# build-logic

Convention plugins shared by all Jotter modules. Each is a precompiled script
plugin in `src/main/kotlin/`, and its id is the file name. Plugin versions come
from `gradle/libs.versions.toml`.

| Plugin | Apply to | What it does |
|---|---|---|
| `jotter.kmp-library` | Every multiplatform library module | Kotlin Multiplatform with `jvm()`, Android (`com.android.kotlin.multiplatform.library`) and `linuxX64()` targets. `linuxX64` is compiled and tested only, never shipped, to keep shared code free of JVM-only APIs ([ADR-0006](../docs/adr/0006-native-compatible-shared-modules.md)). JDK 21 toolchain, `explicitApi()`, warnings as errors. Android: compileSdk 36, minSdk 26, namespace derived from the Gradle path, host tests enabled. Android Lint on the JVM and Android targets, warnings as errors. Adds `kotlin.test` to `commonTest`. Adds detekt's per-target tasks to `check`. |
| `jotter.quality` | Every module | ktlint (style from the root `.editorconfig`), detekt (`config/detekt/detekt.yml` on top of the defaults; any finding fails the build) and Kover instrumentation. For a plain Kotlin/JVM module it also adds the type-resolved detekt tasks to `check`; `jotter.kmp-library` does that for multiplatform modules. |
| `jotter.root` | The root project only | Merges the Kover data of the modules listed as `kover(...)` dependencies in the root `build.gradle.kts` and fails `check` below 95% line or branch coverage. Generated code is excluded here. Also runs ktlint on the root build scripts and on everything in `build-logic/`. |

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

## Known issues

`--warning-mode=fail` cannot be used yet: Kover 0.9.11 calls the deprecated
`Configuration.setVisible`, which Gradle 9.8 reports as a deprecation warning.
