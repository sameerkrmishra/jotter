# build-logic

Convention plugins shared by all Jotter modules. Each is a precompiled script
plugin in `src/main/kotlin/`, and its id is the file name. Plugin versions come
from `gradle/libs.versions.toml`.

| Plugin | Apply to | What it does |
|---|---|---|
| `jotter.kmp-library` | Every multiplatform library module | Kotlin Multiplatform with `jvm()` and Android (`com.android.kotlin.multiplatform.library`) targets. JDK 21 toolchain, `explicitApi()`, warnings as errors. Android: compileSdk 36, minSdk 26, namespace derived from the Gradle path, host tests enabled. Android Lint on both targets, warnings as errors. Adds `kotlin.test` to `commonTest`. |
| `jotter.quality` | Every module | ktlint (style from the root `.editorconfig`), detekt with type resolution (`config/detekt/detekt.yml` on top of the defaults; any finding fails the build) and Kover instrumentation. |
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

## Known issues

`--warning-mode=fail` cannot be used yet: Kover 0.9.11 calls the deprecated
`Configuration.setVisible`, which Gradle 9.8 reports as a deprecation warning.
