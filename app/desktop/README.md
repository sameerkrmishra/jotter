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
a window, because an app started from a menu has no stderr. The reason says what
happened and what to do (`describeOpenFailure`), and the file is left as it is: a
damaged file is never replaced by a new one. It prints the same line
on stderr, and exits with status 1 when the window is closed. That includes a database that stayed locked by
another process for the 5 s busy timeout, which `NoteStore.open` reports as
`StorageBusyException`; it can only happen while another process creates the file
or holds it exclusively.

## Log and debug log

The app logs to `$XDG_STATE_HOME/jotter/logs/desktop.log` (or
`~/.local/state/jotter/logs/desktop.log`), uncaught failures and the hub's events
included; `JOTTER_LOG_LEVEL=debug` changes the level for one run
([ADR-0021](../../docs/adr/0021-local-logging.md)). "Debug log" on the Sync screen, and
"Show debug log" on the screen for notes that cannot be opened, show the whole debug
log (`DebugReport`, with the CLI's log too) and save it, after the user has read it, to
a new file they pick in the desktop's file dialog (`DebugLogFile`; an existing file is
never overwritten). The version it names is the jar's `Implementation-Version`, from
`gradle.properties`.

## Design

- **One composition root** ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)):
  `main()`, in `Main.kt`, excluded with its lambdas from the coverage gate in
  `jotter.root`. `Database.kt` opens the store and is tested.
- **Exit.** Closing the window saves the open editor first. If that fails, the user
  chooses to keep editing or to close without saving. Then `main()` finishes the view models' scope, closes
  the store and calls `exitProcess`. Closing checkpoints the WAL, unless another
  process is reading or writing: then it gives up at once and the last connection
  to close does it (`core:sqlite`). So exit never waits for a lock. `exitProcess`
  also ends the process if an AWT thread hangs after the window closes, as one did
  in spike 0002.
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
- **Runtime modules.** The jlink image gets `java.instrument` and `jdk.unsupported`,
  as `./gradlew :app:desktop:suggestRuntimeModules` says. Run that again when
  dependencies change. `core:sqlite` uses SQLite through JNI, so `java.sql` is not
  needed.
- **`kotlinx-coroutines-swing`** gives `Dispatchers.Main`. Compose does not bring it.

Packaging for release and the CI smoke launch come later.
