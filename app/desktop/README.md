# app:desktop

The Linux desktop app: one window around `app:shared-ui`, in Compose Desktop on the
JVM ([spike 0002](../../docs/spikes/0002-desktop-packaging.md)).

```
./gradlew :app:desktop:run
./gradlew :app:desktop:createDistributable
app/desktop/build/compose/binaries/main/app/jotter/bin/jotter
```

The notes are the CLI's, in the same file: `JOTTER_DB`, then
`$XDG_DATA_HOME/jotter/jotter.db`, then `~/.local/share/jotter/jotter.db`
(`linuxDatabasePath` in `core:storage`). There is no `--db` option. The directory
is created if needed. If the database cannot be opened, the app shows the reason in
a window, because an app started from a menu has no stderr. It prints the same line
on stderr, and exits with status 1 when the window is closed. That includes a database that stayed locked by
another process for the 5 s busy timeout, which `NoteStore.open` reports as
`StorageBusyException`; it can only happen while another process creates the file
or holds it exclusively.

## Design

- **One composition root** ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)):
  `main()`, in `Main.kt`, excluded with its lambdas from the coverage gate in
  `jotter.root`. `Database.kt` opens the store and is tested.
- **Exit.** Closing the window saves the open editor first. If that fails, the user
  chooses to keep editing or to close without saving. Then `main()` finishes the view models' scope, closes
  the store and ends the process through `Shutdown`. Closing checkpoints the WAL, unless another
  process is reading or writing: then it gives up at once and the last connection
  to close does it (`core:sqlite`). So exit never waits for a lock. `Shutdown` waits
  up to 2 s for AWT to shut itself down before `exitProcess`: exiting at once
  deadlocked in about one run in five on Xvfb (libGLX's exit handler waiting for the
  Xlib lock that AWT's toolkit thread held), leaving the process, and the hub's port,
  alive. A daemon watchdog then halts the process if it is still alive 5 s after exit
  began, printing `jotter: exit did not finish, halting`. That is safe only because
  everything is saved and closed by then, so `Shutdown.exit` refuses to run before
  `closed()`, which `main()` calls after the store is closed (`ShutdownTest`).
- **Sync.** `main()` builds `app:sync`'s `BackendManager` and `SyncScheduler` on the
  store and starts the scheduler in the app's scope, so it syncs on start, 5 s after
  edits, every 5 minutes and on "Sync now" ([PLAN.md](../../docs/PLAN.md) §6.3). The
  engine's database work and each backend's I/O run on `Dispatchers.IO`, never on the
  UI thread. Exit cancels the scope, which stops a running sync where it is
  (`SyncEngine` is safe to stop anywhere), and waits for it before the store closes.
  Passwords and tokens come from `app:sync`'s `linuxSecretStores`: the keyring through
  `secret-tool`, or the file the user chose when no keyring answered
  ([ADR-0016](../../docs/adr/0016-where-secrets-live.md)).
- **`--autoclose`** closes the app a few seconds after the window appears and prints
  `jotter: window shown, closing`, to prove that a build starts. It also closes the
  window for a database that cannot be opened.
- **`--version`** prints `jotter X.Y.Z` (the packaged version, or `dev` from `gradlew run`)
  and exits, before any window or the database, so that a package can be checked headless.
- **Runtime modules.** The jlink image gets `java.instrument` and `jdk.unsupported`,
  as `./gradlew :app:desktop:suggestRuntimeModules` says. Run that again when
  dependencies change. `core:sqlite` uses SQLite through JNI, so `java.sql` is not
  needed.
- **`kotlinx-coroutines-swing`** gives `Dispatchers.Main`. Compose does not bring it.

## The AppImage

Releases ship the app as `Jotter-X.Y.Z-x86_64.AppImage`, with its own Java runtime and the
CLI inside ([ADR-0020](../../docs/adr/0020-linux-desktop-package.md)). To build one, with
squashfs-tools 4.6 or newer installed (or `MKSQUASHFS` set to its `mksquashfs`):

```
app/desktop/scripts/appimage.sh                 # build/dist/Jotter-X.Y.Z-x86_64.AppImage
app/desktop/scripts/smoke-appimage.sh build/dist/Jotter-*-x86_64.AppImage
app/desktop/scripts/reproducible-build.sh       # builds it twice, compares
```

`:app:desktop:appImageDir` assembles the directory in `build/appimage/Jotter.AppDir`, and
`smoke-appimage.sh` also takes that directory. The smoke test runs the package in an empty
environment with temporary `HOME`, XDG directories and `JOTTER_DB`: `--version`, then
`cli add` and `cli list` (again with `-Xshare:on`, which fails without the runtime's class
data archive), and `AppRun`'s choice for the names `jotter`, the desktop entry's `Exec` and
another name. With
`JOTTER_SMOKE_GUI=1` it also opens the window under `xvfb-run` with `--autoclose`, which the
release workflow does; it never uses the caller's display.

For users:

```
chmod +x Jotter-1.0.0-x86_64.AppImage
./Jotter-1.0.0-x86_64.AppImage                  # the app
./Jotter-1.0.0-x86_64.AppImage cli list         # the CLI
ln -s "$PWD/Jotter-1.0.0-x86_64.AppImage" ~/.local/bin/jotter
jotter list                                     # the CLI, under the name jotter
```

`AppRun` chooses: the CLI when the first argument is `cli` or the AppImage was started as
`jotter`, the app under any other name. The desktop entry's `Exec` is therefore
`jotter-desktop`, never `jotter`; tools that add AppImages to the menu replace it with the
file's path. After an update, point the symlink at the new file. Without
FUSE, `--appimage-extract-and-run` or `APPIMAGE_EXTRACT_AND_RUN=1` runs it all the same.
The CLI inside starts more slowly than the standalone one (see `app/cli`'s README); the
release's `jotter-cli-X.Y.Z.zip` is the standalone one, for machines with Java 21.
