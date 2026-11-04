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
  The JDK is named in the build (`javaHome`, the toolchain), not left to Compose's default.
  Right after jpackage, `createDistributable` changes three things: it writes Skiko's runtime
  jar again (Compose 1.12.2 strips its native library and writes the rest with the clock's
  time, which also changed the jar's hashed name), it puts the JDK's own `bin/java` back into
  the runtime (Compose's jlink strips `bin/`), for the CLI, and it makes the runtime's base
  class data archive, `lib/server/classes.jsa`, which jlink does not, with `java -Xshare:dump`
  in an empty environment (the locale is part of the archive).
- `opt/jotter-cli/`: `app:cli`'s jars and a start script of its own
  (`appimage-script.template`), which runs that `java` with the standalone script's flags and
  the base archive, but without the CLI's own (dynamic) class data archive, see below.
- `AppRun`: the CLI when the first argument is `cli`, or when the AppImage was started under
  the name `jotter`; the desktop app under any other name. So `ln -s
  ~/Applications/Jotter-*.AppImage ~/.local/bin/jotter` puts the CLI on `PATH`, and the
  AppImage itself stays the app.
- A desktop entry, AppStream metadata and the icon, named `io.github.sameerkrmishra.jotter`.
  The entry's `Exec` is `jotter-desktop`, so that it never names the CLI.

**Packing** (`app/desktop/scripts/appimage.sh`): mksquashfs over the directory, with zstd, then
AppImage's `type2-runtime` in front of it, which is all appimagetool does. The runtime is a fixed
release (20251108), downloaded by URL and checked against its SHA-256 in the script. It is
statically linked, so the host needs FUSE but no libfuse2, and `--appimage-extract-and-run` (or
`APPIMAGE_EXTRACT_AND_RUN=1`) runs it without FUSE. No update information is embedded: users update by downloading the next AppImage, as the
app's "no network except your backends" promise and the missing updater imply.

**Determinism.** Gradle gives every directory and executable mode 755 and every other file 644,
so the umask does not leak in; mksquashfs sorts the entries, makes root the owner of each and
sets every time to 0. Two builds of one commit with the same JDK
build and the same squashfs-tools version give the same AppImage, and the CLI zip is the same
too: `app/desktop/scripts/reproducible-build.sh` checks both the way ADR-0019's script checks
the APK (two `git archive` copies, different time zones, locales and umasks, a fresh Gradle user
home for the second), and the `Reproducible` workflow runs it as its `desktop` job. Unlike the
APK, the AppImage cannot be the same across JDK builds or vendors: the runtime it contains *is*
the JDK's jlink image. And another squashfs-tools version may compress differently. So the
release workflow builds on a pinned runner image (`ubuntu-24.04`, its squashfs-tools) and writes
both versions into `BUILDINFO.txt`; to check a release elsewhere one uses those, or compares the
unpacked contents, which do not depend on mksquashfs.

The script checks that each copy built its own package, and starts the second build in a later
minute than the first ended. Its first version passed when it should not have: `appimage.sh`
found its root through git, so in a `git archive` copy under `build/` it packed the enclosing
checkout, twice. With that fixed it found three differences, all now gone: the Skiko jar's
entry times, the locale in `classes.jsa`, and the mode of the image's root directory (700 under
umask 077).

**No class data archive of the CLI's own.** A dynamic archive made at build time would start
the CLI in the AppImage faster, and JDK 21 accepts it at another path (the mount point), but
JDK 21 does not write it deterministically: two `-XX:ArchiveClassesAtExit` runs of the same
`jotter add`, at the same path with the same flags (also with `-Xint`, also on one processor),
differed in about 4 MB of 6.4 MB. It would make the AppImage different on every build, so it is
left out. The base archive, `classes.jsa`, is deterministic and in the image; the smoke test
checks that the CLI can use it (`-Xshare:on`). One made at run time is not possible either:
the image is read-only, and every file in it has the same time, so an archive in the user's
cache could not be told stale after an update.

While this was decided, on 2026-11-04: `reproducible-build.sh` built `c3e61d4` four times, in
two runs, locally (Temurin 21.0.12.1, squashfs-tools 4.7.5), and gave the same AppImage
(`65fdc401…`, 74,263,032 bytes) and CLI zip (`59e3ba4f…`, 13,048,226 bytes) each time. The
release workflow's dry run on CI, of an earlier commit with Temurin 21.0.13 and squashfs-tools
4.6.1, gave the same CLI zip and the same unsigned APK as M5's notes record (`a5d606ab…`).

**Release.** `.github/workflows/release.yml` builds the unsigned APK, the AppImage and the CLI
zip on a `v*` tag, runs the AppImage's smoke test (`smoke-appimage.sh`: `--version`, the CLI's
`add` and `list` with temporary directories, the base class data archive, `AppRun`'s choice for
each name, and the window on Xvfb), writes `SHA256SUMS`, and
creates a draft release. The maintainer signs the APK and publishes ([RELEASING.md](../../RELEASING.md)).

## Consequences

### Positive

- The AppImage sees the same files, keyring, network and processes as any program of the user:
  folders on USB sticks, the shared database with the CLI, `secret-tool` and the hub on the
  Tailscale address work as they do from the source tree, with nothing to grant.
- One download, about 74 MB, for the app and the CLI, with no Java to install.
- The package is checked headless in CI on every release, window included, and a change that
  makes it non-deterministic fails on `main`.

### Negative

- No sandbox: the AppImage can do whatever its user can, like the CLI.
- No automatic updates and no desktop integration by itself: the user puts the file somewhere,
  makes it executable, and adds it to the menu (or uses a tool such as Gear Lever or
  AppImageLauncher). The desktop entry inside makes that work.
- AppImages need FUSE to mount themselves. Most desktops have it; elsewhere
  `--appimage-extract-and-run` works.
- The CLI inside the AppImage is a convenience, not the fast path: it has no class data archive
  of its own, and the AppImage mounts itself on every run. ADR-0006's 200 ms target is for the
  standalone CLI (the zip), which needs Java 21. Median of 10 runs of `jotter add`, after a
  warm-up, on the machine in app/cli's README with a one-minute load average under 1.6 of 12
  threads, 2026-11-04, in two rounds: the zip's CLI 146 and 145 ms; the AppImage through a
  symlink named `jotter`, mounted with FUSE, 453 and 443 ms; the same with
  `APPIMAGE_EXTRACT_AND_RUN=1`, 385 and 382 ms. Fine at a prompt, slow in a loop.
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
- **A class data archive for the CLI inside the AppImage**, made at build time: it would speed
  up the CLI in the AppImage (the review of this decision measured 296 ms mounted with one,
  against 557 to 623 ms without, before the base archive was added), but JDK 21 does not write it deterministically (above).
  Worth trying again with a later JDK, under the same check.
- **The CLI on `PATH` through a wrapper script installed by the AppImage**: it would write outside
  the AppImage on first run; a symlink the user makes needs nothing.
