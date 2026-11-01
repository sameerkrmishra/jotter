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
is created if needed. If the database cannot be opened, the app prints one line on
stderr and exits with status 1.

## Design

- **One composition root** ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)):
  `main()`, in `Main.kt`, excluded with its lambdas from the coverage gate in
  `jotter.root`. `Database.kt` opens the store and is tested.
- **Exit.** Closing the window saves the open editor first, and stays open if that
  fails, so the message shows. Then `main()` finishes the view models' scope, closes
  the store, which checkpoints the WAL, and calls `exitProcess`. That also ends the
  process if an AWT thread hangs after the window closes, as one did in spike 0002.
- **`--autoclose`** closes the app a few seconds after the window appears and prints
  `jotter: window shown, closing`, to prove that a build starts.
- **Runtime modules.** The jlink image gets `java.instrument` and `jdk.unsupported`,
  as `./gradlew :app:desktop:suggestRuntimeModules` says. Run that again when
  dependencies change. `core:sqlite` uses SQLite through JNI, so `java.sql` is not
  needed.
- **`kotlinx-coroutines-swing`** gives `Dispatchers.Main`. Compose does not bring it.

Packaging for release and the CI smoke launch come later.
