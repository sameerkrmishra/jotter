# 0020. The Linux package: an AppImage with the CLI inside, not a Flatpak

- **Status:** Proposed
- **Date:** 2026-11-04

## Context

M6 ships v1.0 "with APK + AppImage/Flatpak" ([PLAN.md §10](../PLAN.md#10-milestones)). Until
now the desktop app existed only as Compose's `createDistributable` directory, and the CLI as
Gradle's `installDist`, which needs Java 21 on the machine
([spike 0002](../spikes/0002-desktop-packaging.md)). Users need one download that runs on any
current x86_64 distribution without Java, and a way to put `jotter` on `PATH`.

What the app does decides the format more than the formats themselves:

- **Local folders.** A backend can be any folder the user types in, such as a USB stick under
  `/run/media/USER/STICK` ([ADR-0012](0012-local-folder-publishing.md)). The path is stored and
  opened again on every sync, and ADR-0012 tells a store from a bare mount point by looking at
  the folder and its parents.
- **One database for two programs.** The app and the CLI share `~/.local/share/jotter/jotter.db`
  (or `JOTTER_DB`), and the hub's store and settings live under the XDG directories.
- **Secrets.** Passwords and tokens go to the keyring through `secret-tool`, which the app runs
  as a process, or to a file the user chose ([ADR-0016](0016-where-secrets-live.md)). The CLI
  must find what the app stored, and the other way round.
- **The hub.** The PC listens on its Tailscale address for the phone
  ([ADR-0015](0015-pc-hub-peer-protocol.md)) and checks the peer's address.
- **Updates.** The app has no updater and contacts no server except the user's backends.
- **Reproducible builds** are the rule for the APK ([ADR-0019](0019-release-builds.md)).

## Decision

**Linux gets an AppImage, `Jotter-X.Y.Z-x86_64.AppImage`, and no Flatpak for v1.0.** It holds
the desktop app, the CLI and one Java runtime; the CLI is also released alone as
`jotter-cli-X.Y.Z.zip`, for Java 21 or newer.

**Contents** (`:app:desktop:appImageDir`, a Gradle `Sync`):

- `opt/jotter/`: Compose's distributable, as before: jpackage's launcher, the app's jars and a
  jlink image of the build's JDK 21 toolchain with the modules Compose and `modules(...)` name.
  Compose's jlink strips the image's `bin/`, so the task adds that JDK's own `bin/java` to it,
  for the CLI. The JDK is named in the build (`javaHome`, the toolchain), not left to Compose's
  default.
- `opt/jotter-cli/`: `app:cli`'s jars and a start script of its own
  (`appimage-script.template`), which runs that `java` with the standalone script's flags but
  without its class data archive: the AppImage is mounted at a new path on each run, and every
  file in it has the same time, so an archive could neither match nor be seen as stale.
- `AppRun`: starts the desktop app; the CLI when the first argument is `cli`, or when the
  AppImage was started under the name `jotter`. So `ln -s ~/Applications/Jotter-*.AppImage
  ~/.local/bin/jotter` puts the CLI on `PATH`, and the AppImage itself stays the app.
- A desktop entry, AppStream metadata and the icon, named `io.github.sameerkrmishra.jotter`.

**Packing** (`app/desktop/scripts/appimage.sh`): mksquashfs over the directory, with zstd, then
AppImage's `type2-runtime` in front of it, which is all appimagetool does. The runtime is a fixed
release (20251108), downloaded by URL and checked against its SHA-256 in the script. It is
statically linked, so the host needs FUSE but no libfuse2, and `--appimage-extract-and-run` (or
`APPIMAGE_EXTRACT_AND_RUN=1`) runs it without FUSE. No update information is embedded: users update by downloading the next AppImage, as the
app's "no network except your backends" promise and the missing updater imply.

**Determinism.** Gradle gives every directory and executable mode 755 and every other file 644,
so the umask does not leak in; mksquashfs sorts the entries, makes root the owner of each and
sets every time to `SOURCE_DATE_EPOCH` (0 by default). Two builds of one commit with the same JDK
build and the same squashfs-tools version give the same AppImage, and the CLI zip is the same
too: `app/desktop/scripts/reproducible-build.sh` checks both the way ADR-0019's script checks
the APK (two `git archive` copies, different time zones, locales and umasks, a fresh Gradle user
home for the second), and the `Reproducible` workflow runs it as its `desktop` job. Unlike the
APK, the AppImage cannot be the same across JDK builds or vendors: the runtime it contains *is*
the JDK's jlink image. And another squashfs-tools version may compress differently. So the
release workflow builds on a pinned runner image (`ubuntu-24.04`, its squashfs-tools) and writes
both versions into `BUILDINFO.txt`; to check a release elsewhere one uses those, or compares the
unpacked contents, which do not depend on mksquashfs.

While this was decided, on 2026-11-04: `reproducible-build.sh` built `d56c782` twice locally
(Temurin 21.0.12.1, squashfs-tools 4.7.5) and gave the same AppImage (71,223,800 bytes) and CLI
zip (13,048,226 bytes) both times. The release workflow's dry run on CI, the same tree with
Temurin 21.0.13 and squashfs-tools 4.6.1, gave a different AppImage, as expected, but the same
CLI zip (`59e3ba4f…`) and the same unsigned APK as M5's notes record (`a5d606ab…`).

**Release.** `.github/workflows/release.yml` builds the unsigned APK, the AppImage and the CLI
zip on a `v*` tag, runs the AppImage's smoke test (`smoke-appimage.sh`: `--version`, the CLI's
`add` and `list` with temporary directories, and the window on Xvfb), writes `SHA256SUMS`, and
creates a draft release. The maintainer signs the APK and publishes ([RELEASING.md](../../RELEASING.md)).

## Consequences

### Positive

- The AppImage sees the same files, keyring, network and processes as any program of the user:
  folders on USB sticks, the shared database with the CLI, `secret-tool` and the hub on the
  Tailscale address work as they do from the source tree, with nothing to grant.
- One download, about 70 MB, for the app and the CLI, with no Java to install.
- The package is checked headless in CI on every release, window included, and a change that
  makes it non-deterministic fails on `main`.

### Negative

- No sandbox: the AppImage can do whatever its user can, like the CLI.
- No automatic updates and no desktop integration by itself: the user puts the file somewhere,
  makes it executable, and adds it to the menu (or uses a tool such as Gear Lever or
  AppImageLauncher). The desktop entry inside makes that work.
- AppImages need FUSE to mount themselves. Most desktops have it; elsewhere
  `--appimage-extract-and-run` works.
- The CLI inside the AppImage is slower to start than the standalone one (about 150 ms): no class
  data archive, and a FUSE mount per run. `jotter add` through the symlink took a median of
  0.73 s mounted and 0.54 s with `APPIMAGE_EXTRACT_AND_RUN=1` (10 runs each, 2026-11-04, on the
  machine in app/cli's README, but busy: load average 9 on 12 threads), so a quiet machine should
  do better. Fine at a prompt, slow in a loop; the zip keeps the fast path, but needs Java.
- x86_64 only, as Compose's Skiko library in the build is.
- The runtime is downloaded at build time from GitHub, though pinned by hash; and reproducing
  CI's AppImage byte for byte needs CI's JDK build and squashfs-tools.

## Alternatives considered

- **Flatpak, on Flathub or as a bundle.** Updates would come for free and the app would be
  sandboxed, but the sandbox is what breaks the app: a backend folder typed as a path, such as a
  USB stick, is outside it unless the manifest grants `--filesystem=host` (or `/run/media` and
  `/media` and wherever else users mount), which Flathub's reviewers question and which ends the
  sandbox anyway; the document portal would hand out `/run/user/…/doc/…` paths, on which
  ADR-0012's mount-point check means nothing. Inside, `$XDG_DATA_HOME` and `$XDG_CONFIG_HOME` are
  the app's own under `~/.var/app`, so the CLI and the app would see two databases and two
  secrets files, unless both run inside it (`flatpak run --command=jotter …`, which then needs the
  same grants for `import` and `export`). The keyring needs `--talk-name=org.freedesktop.secrets`
  and a `secret-tool` in the runtime. Flathub also builds from source without network, which
  needs every Gradle dependency listed as an offline source. Worth doing once the app picks
  folders through the portal's file chooser and keeps secrets through the Secret portal; not for
  v1.0, and `flatpak-builder` was not available to try it.
- **A `.deb` or `.rpm`** (Compose's `packageDeb`, `packageRpm`): per distribution family, needs
  root to install, and jpackage derives dependencies from the build host (spike 0002). Not unless
  asked.
- **appimagetool** to pack: unless given a runtime file, it downloads the latest runtime at every
  run, and it is one more unpinned binary between the directory and the image. Calling mksquashfs
  directly is two commands, with every flag in view.
- **jpackage's `--type app-image`** (Compose's `packageAppImage`): only the directory, not an
  AppImage (spike 0002).
- **A second, smaller jlink image for the CLI**: about 50 MB more, for a runtime that differs from
  the app's only in having `bin/java`.
- **A class data archive for the CLI inside the AppImage**, made at build time: it would be made
  for the build's paths, not the mount point's, and would be one more file to keep deterministic;
  one made at run time could not be told stale (above).
- **The CLI on `PATH` through a wrapper script installed by the AppImage**: it would write outside
  the AppImage on first run; a symlink the user makes needs nothing.
