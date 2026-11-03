# F-Droid

What F-Droid needs from Jotter, and what was checked
([PLAN.md §8](../PLAN.md#8-quality-bar), [ADR-0019](../adr/0019-release-builds.md)).

- **Store listing:** `fastlane/metadata/android/en-US/` in the repository root, which F-Droid
  reads from the source: `title.txt`, `short_description.txt` (at most 80 characters),
  `full_description.txt`, `changelogs/<versionCode>.txt` and `images/icon.png` (512 × 512, drawn
  from the launcher icon's vector, `res/drawable/ic_launcher_foreground.xml` on its background
  colour, cut to the adaptive icon's visible 72 dp). No screenshots yet.
- **Build recipe:** [io.github.sameerkrmishra.jotter.yml](io.github.sameerkrmishra.jotter.yml),
  a draft for `metadata/` in [fdroiddata](https://gitlab.com/fdroid/fdroiddata). The maintainer
  submits it; [docs/releasing.md](../releasing.md) has the release steps it relies on.

## Before submitting

1. Make the first release (docs/releasing.md), and fill in the recipe's `commit` and
   `AllowedAPKSigningKeys`.
2. Check the recipe's `sudo` lines against F-Droid's current build server image: they install
   JDK 21, which the build requires.
3. Run `fdroid lint` and `fdroid build -v -l io.github.sameerkrmishra.jotter` in a checkout of
   fdroiddata with the recipe in `metadata/`, if possible on F-Droid's build server image
   (`fdroidserver` in Docker). Neither has been run.
4. Look at what `fdroid scanner` reports. Expected: `gradle/wrapper/gradle-wrapper.jar`, which
   F-Droid replaces with its own Gradle, and possibly the SQLDelight schema snapshots
   (`core/storage/src/commonMain/sqldelight/databases/*.db`, SQLite files used only by the
   migration tests); if the scanner flags them, a `scandelete` line for that folder is safe, as
   `assembleRelease` does not read them.

## Inclusion rules

| Rule | Status |
|---|---|
| Free licence | Apache-2.0 (`LICENSE`, `NOTICE`) |
| No proprietary libraries (Play Services, Firebase, crash reporters, analytics) | None, see the audit below. `settings.gradle.kts` resolves only `com.android` and `androidx` from Google's repository, so Play Services and Firebase cannot be resolved by accident |
| No non-free network services, no tracking | The app connects only to WebDAV servers and PC hubs that the user adds. No telemetry, crash reports or update checks |
| Built from source, with no prebuilt binaries | The app's code is all built from source. Libraries are Maven artifacts from Maven Central and Google's AndroidX repository, which F-Droid accepts. `androidx.sqlite:sqlite-bundled` brings a prebuilt SQLite native library inside its AAR, from AndroidX |
| No Google dependency metadata block | Off (`dependenciesInfo`, ADR-0019) |
| Reproducible | Yes: `app/android/scripts/reproducible-build.sh`, and CI's `reproducible` workflow |

**Anti-features:** none expected. Nothing would need `Ads`, `Tracking`, `NonFreeNet`,
`NonFreeDep`, `NonFreeAdd` or `NonFreeComp`. Two things a reviewer may ask about:

- **`androidx.emoji2`** (through Compose) registers `EmojiCompatInitializer`, which looks for a
  *system-installed* downloadable-emoji font provider, on most phones Google Play Services'. It
  does not bundle or require Play Services, and on a phone without it emoji fall back to the
  system font. F-Droid has not treated this as `NonFreeDep` for other Compose apps; removing the
  initializer from the manifest would avoid the question, at the cost of newer emoji on old
  Android versions.
- **Tailscale** is a third-party app the PC hub relies on, not a dependency of Jotter: Jotter
  only checks whether a VPN with a Tailscale address carries it (ADR-0018). Tailscale's Android
  client is open source, and its coordination server can be self-hosted (Headscale). WebDAV sync
  needs nothing of the kind.

## Dependency audit

The release variant's runtime classpath of `app:android` (`releaseRuntimeClasspath`), with the
version catalog of 2026-11-04 (commit `1b02e09`): 197 artifacts, counting each multiplatform
library's `-jvm` or `-android` variant apart, none proprietary. Licences are
from each artifact's POM (or its parent's).

| Group | Artifacts | Licence |
|---|---|---|
| `androidx.*` (activity, annotation, arch.core, autofill, collection, compose.\*, concurrent, core, customview, emoji2, graphics, interpolator, lifecycle, navigationevent, profileinstaller, room, savedstate, sqlite, startup, tracing, versionedparcelable, window, work) | 100 | Apache-2.0 |
| `org.jetbrains.compose.*`, `org.jetbrains.androidx.*` (Compose Multiplatform) | 35 | Apache-2.0 |
| `io.ktor` (client core, CIO and OkHttp engines, HTTP, I/O, network, TLS, serialization, SSE, websockets) | 28 | Apache-2.0 |
| `org.jetbrains.kotlinx` (coroutines, serialization, atomicfu, kotlinx-io) | 16 | Apache-2.0 |
| `org.jetbrains.kotlin` (stdlib) | 2 | Apache-2.0 |
| `org.jetbrains` (annotations, the Markdown parser) | 3 | Apache-2.0 |
| `app.cash.sqldelight` (runtime, coroutines and async extensions) | 6 | Apache-2.0 |
| `com.squareup.okhttp3` (OkHttp, for the hub client on the tailnet) | 2 | Apache-2.0 |
| `com.squareup.okio` | 2 | Apache-2.0 |
| `com.google.guava:listenablefuture` (an empty placeholder artifact, through WorkManager) | 1 | Apache-2.0 |
| `org.jspecify:jspecify` | 1 | Apache-2.0 |
| `org.slf4j:slf4j-api` (through Ktor; no logging backend is included, so nothing logs) | 1 | MIT |

`androidx.room` comes with WorkManager, which keeps its job queue in a Room database. To repeat
the audit:

```
./gradlew -q :app:android:dependencies --configuration releaseRuntimeClasspath
```

and read the `<licenses>` of each POM under `~/.gradle/caches/modules-2/files-2.1/`.
