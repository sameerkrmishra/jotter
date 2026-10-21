# Spike 0002: Compose Desktop packaging on Linux

Status: done (M0). Spike code, for reference only: branch `spike/desktop-code`
(module `spike/desktop`, a `jotter.desktop-app` convention plugin, lint tweaks).

## Question

Can we build, package and run a Compose Multiplatform desktop app on Linux with
our toolchain and quality gates, which formats can we ship, and what are the
Wayland, HiDPI and IME caveats for a text-heavy notes app?

## Setup

- Kotlin 2.4.21, Gradle 9.8.2 (configuration cache on), JDK 21 toolchain
  (Temurin 21.0.12.1, foojay), AGP 9.4.1 on the same build-logic classpath.
- Compose Multiplatform **1.12.2** (`org.jetbrains.compose`, latest stable;
  1.13.0 is alpha). Compose compiler: `org.jetbrains.kotlin.plugin.compose`
  **2.4.21**, i.e. always the Kotlin version (`version.ref = "kotlin"`).
- Libraries by explicit coordinates: `org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.12.2`
  and `org.jetbrains.compose.material3:material3:1.9.0`. Material3 is versioned
  separately from CMP since 1.10; 1.9.0 is its latest stable.
- Host: EndeavourOS (Arch), Hyprland (Wayland) session with XWayland,
  `DISPLAY=:1`, `WAYLAND_DISPLAY=wayland-1`, one 2560x1440 monitor at scale 1.
- App: one window, `OutlinedTextField` + Add button + `LazyColumn`, Material3.
  An env var `JOTTER_SPIKE_AUTOCLOSE` makes it exit by itself after 4 s.

## Results

### 1. Build and quality gates: works, with three small build-logic changes

`./gradlew check` (whole build, including `:core:model`) passes with the spike
module applying `jotter.desktop-app` + `jotter.quality`. Only warning is the
known Kover `Configuration.setVisible` deprecation; CMP adds none.
`explicitApi()` and `allWarningsAsErrors` cause no trouble (`main` is `public`,
composables `private`). Needed:

1. **New convention plugin** `jotter.desktop-app`: `kotlin.jvm` +
   `kotlin.plugin.compose` + `org.jetbrains.compose`, `explicitApi()`,
   `jvmToolchain(21)`, warnings as errors. build-logic gets two dependencies:
   `org.jetbrains.compose:compose-gradle-plugin` and
   `org.jetbrains.kotlin:compose-compiler-gradle-plugin`.
2. **`jotter.quality` hard-codes the KMP detekt task names**
   (`detektMainAndroid`, ...), so a Kotlin/JVM module fails. The spike picks the
   list with `pluginManager.withPlugin(...)` (`detektMain`/`detektTest` for JVM).
3. **PascalCase composables** fail both linters. Fixed by
   `naming.FunctionNaming.ignoreAnnotated: ['Composable']` (detekt) and
   `ktlint_function_naming_ignore_when_annotated_with = Composable` (.editorconfig).
   `shared-ui` (KMP) will need the same, so this belongs on `main` in M2.

Kover: line coverage of the module was 3% because Compose lambdas compile to
many synthetic classes. The 95% gate cannot apply to UI code via unit tests;
see Recommendation.

### 2. Packaging formats

`org.jetbrains.compose` registers `createDistributable`, `packageAppImage`,
`packageDeb`, `packageRpm`, `packageUberJarForCurrentOS` (plus `Release`
variants with ProGuard). All except the uber jar call `jpackage` from the
toolchain JDK.

| Task | Result | Size |
|---|---|---|
| `createDistributable` | OK, `build/compose/binaries/main/app/jotter-spike/` | 143 MB dir (runtime 87 MB, app jars 55 MB of which `libskiko-linux-x64.so` 30 MB); 67 MB `.tar.gz` |
| `createReleaseDistributable` (ProGuard on, `optimize = true`) | OK | 127 MB dir, app jars 39 MB; 52 MB `.tar.gz` |
| `packageAppImage` | OK, but it is **jpackage `--type app-image`**: the same directory as the distributable, **not** an `.AppImage` file | same as above |
| `packageDeb` | Failed without `dpkg-deb` (`Error: Invalid or unsupported type: [deb]`). With a user-space `dpkg-deb` 1.23.11 on `PATH`: OK | 54 MB `.deb` (installs to `/opt/jotter-spike`, Installed-Size 145 MB) |
| `packageRpm` | Failed: `rpmbuild` not installed (`Invalid or unsupported type: [rpm]`); not attempted further | – |
| `packageUberJarForCurrentOS` | OK | 38 MB jar, **no JDK** (needs Java 21+ on the host) |

- The JDK runtime **is bundled** in distributable/AppImage/deb/rpm: a jlink
  image with `--strip-debug --no-header-files --no-man-pages --strip-native-commands`
  and modules `java.base java.desktop java.logging jdk.crypto.ec` plus what we
  add. `suggestRuntimeModules` suggested `java.instrument, jdk.unsupported`.
- Shrinking: ProGuard saves ~16 MB on disk / 15 MB compressed. Skiko's 30 MB
  native library and the ~53 MB `lib/modules` dominate; jlink `--compress` is not
  exposed by the DSL. Realistic floor is ~50 MB compressed.
- The `.deb` built on Arch declares only `Depends: xdg-utils`, because
  jpackage derives library dependencies with `dpkg -S`. Build debs on a
  Debian/Ubuntu CI runner if we ever ship them.
- A real `.AppImage` needs `appimagetool` (not installed) around the app-image
  dir plus `AppRun`, `.desktop` file and icon; a small Gradle task or CI step.

### 3. Does the packaged app start? Yes, seen on screen

A display was available, so no Xvfb (not installed anyway). Launched each
artifact with auto-close; exit code 0 and `jotter-spike: window shown, closing`
on stdout for: debug distributable (3 runs), ProGuard release distributable
(2 runs), uber jar on Temurin 21. Each run took 5.4-6.0 s, i.e. ~1.5 s of
startup and shutdown on top of the 4 s delay. Screenshots via ImageMagick
`import -window <X11 id>` show the text field, button and both list items,
`Grüße — ünïcödé ✓` included. (`import -window root` fails on Hyprland; no `grim`.)

One anomaly: in the first screenshot run the JVM hung after `exitApplication()`
and ignored SIGTERM and SIGQUIT until `kill -9`. Not reproduced in the 6 other
runs (4 after it, with and without screenshots). Watch for it in M2.

### 4. Flatpak: feasible, not built

`flatpak` and `flatpak-builder` are not installed. Path: a manifest on
`org.freedesktop.Platform` that unpacks our release distributable tarball
(it already contains its own jlinked JDK), installs a `.desktop` file, icon and
AppStream metainfo, and grants `--socket=x11` (or `fallback-x11` + `wayland`
later), `--share=ipc`, `--share=network` (sync), `--device=dri`. Flathub accepts
manifests that repackage upstream release binaries; building from source on
Flathub would need all Gradle dependencies pre-listed as offline sources
(e.g. a generated sources JSON), which is much more work.

### 5. Linux distribution channels

F-Droid is Android-only. Realistic for Linux: (1) **GitHub Releases** with a
`.tar.gz` of the distributable and an `.AppImage`, built in CI; (2) **Flathub**
later (M6); (3) a `.deb` only on demand, built on Ubuntu. Reproducibility:
jpackage/jlink outputs and `.deb` contain timestamps and are not bit-for-bit
reproducible; the bundled runtime depends on the exact toolchain JDK build
(pin vendor and version in CI); Skiko ships a prebuilt native library from
Maven Central rather than one we build. Publish SHA-256 sums with releases.

### 6. Wayland vs X11, HiDPI, IME

- **XWayland only.** `hyprctl clients` reports the window as `xwayland: 1`
  and it appears in the X11 `_NET_CLIENT_LIST`. Stock OpenJDK 21 AWT has only
  an X11 toolkit; Skiko renders into an AWT window. Native Wayland would need
  JetBrains Runtime's experimental `WLToolkit` (not tested; not production-ready
  for Compose as far as we know).
- **HiDPI** (not testable here, scale 1): under XWayland, fractional scaling is
  either blurry (compositor upscales) or needs `sun.java2d.uiScale` /
  `GDK_SCALE` set; we may want a launcher option or setting.
- **IME** (not tested): text input goes through XIM under XWayland, so
  IBus/Fcitx5 must have their XIM frontend enabled; compose keys and
  dead keys work through it. Pre-edit window placement in Compose text fields
  on Linux has had bugs historically. Test with Fcitx5 (CJK) and dead keys in M2,
  since the editor is the core of the app.

## Recommendation for M2

- Add to the catalog: `compose-multiplatform = "1.12.2"`,
  `compose-material3 = "1.9.0"`; compiler plugin from the Kotlin version.
- Add `jotter.desktop-app` to build-logic (as in the spike) for `app/desktop`;
  `app/shared-ui` stays `jotter.kmp-library` + `kotlin.plugin.compose` +
  `org.jetbrains.compose` (a `jotter.compose` add-on plugin is the cleanest).
- Make `jotter.quality` choose detekt tasks per Kotlin plugin, and add the
  `@Composable` naming exemptions for detekt and ktlint.
- Coverage: keep `app/desktop` to a thin `main()` and exclude it in
  `jotter.root` (`*.MainKt`, `ComposableSingletons*`); test screens in
  `shared-ui` with Compose UI tests (`runComposeUiTest` on the JVM).
- Ship format: ProGuard release `createReleaseDistributable` as `.tar.gz` plus
  an `.AppImage` made with `appimagetool` in CI; set `packageVersion` from the
  Git tag. Flatpak (Flathub) in M6. Skip rpm; deb only if asked.
- Set `modules(...)` explicitly and re-run `suggestRuntimeModules` when
  dependencies change (SQLDelight's JDBC driver will need `java.sql`).

## Risks

- XWayland-only: HiDPI blur and IME quirks on Wayland desktops; no native
  Wayland until JBR's toolkit matures.
- ~50 MB downloads per release, mostly Skiko and the JDK.
- Material3 versioning is decoupled from CMP; upgrades need two version bumps.
- `packageAppImage` naming is misleading; the real AppImage step is ours to own.
- The unreproduced shutdown hang (section 3).
