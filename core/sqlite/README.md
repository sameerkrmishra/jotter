# core:sqlite

A synchronous SQLDelight `SqlDriver` over androidx `sqlite-bundled`, so Android,
the JVM and linuxX64 all run the same SQLite build with FTS5
([ADR-0002](../../docs/adr/0002-sqlite-via-sqldelight.md)). It knows nothing
about Jotter's schema: `core/storage` owns the `.sq` files, the SQLDelight
plugin (`generateAsync = false`, `sqlite-3-44-dialect`) and the repositories.

```kotlin
val driver: SqlDriver = openSqliteDriver(path, JotterDatabase.Schema)
val db = JotterDatabase(driver)
// ...
driver.close() // checkpoints the WAL
```

**Dependency rule:** depends only on SQLDelight's runtime, androidx SQLite and
atomicfu. No other Jotter module.

## What `openSqliteDriver` does

- Opens (or creates) the file and sets `busy_timeout = 5000`,
  `journal_mode = WAL` (fails if WAL cannot be enabled, e.g. `:memory:`) and
  `foreign_keys = ON`.
- Creates or migrates the schema inside one `BEGIN IMMEDIATE` transaction,
  using `PRAGMA user_version`. A database with a newer version than the schema
  is rejected and left untouched.

## Design

- **One connection, one mutex.** Every call takes a reentrant lock, so any
  thread may use the driver. The outermost transaction holds the lock from
  `newTransaction()` to `endTransaction()`, so other threads wait for the whole
  transaction instead of running inside it. Thread confinement does not fit
  SQLDelight's synchronous API, where the transaction body runs on the
  caller's thread. Consequences: a transaction begins and ends on one thread
  (the synchronous API guarantees it; the suspending API and
  `generateAsync = true` are not supported), and a transaction body must not
  wait for another thread that uses the database (deadlock).
- **`transaction(noEnclosing = true)` inside a transaction** throws, as in
  SQLDelight, and also rolls back the whole outer transaction: SQLDelight
  leaves an unfinished nested transaction behind, after which its record of
  failures and hooks cannot be trusted.
- **Reads.** Every transaction uses `BEGIN IMMEDIATE`, read-only ones too, and
  so takes the write lock across processes. `core/storage` should therefore
  not wrap pure reads in transactions; a single query needs none.
- **Transactions.** Only the outermost one runs SQL: `BEGIN IMMEDIATE`, then
  `COMMIT` or `ROLLBACK`. Nested transactions join it (SQLDelight semantics):
  if any of them fails, everything rolls back. A failed `COMMIT` (deferred
  foreign key) is rolled back, so the connection never stays in a transaction.
  `afterCommit`/`afterRollback` and listener notifications are handled by
  SQLDelight's `TransacterImpl` after the lock is released.
- **Listeners.** A separate lock guards the listener map. Lock order is
  connection lock, then listener lock, never the reverse; listeners are
  called with neither held by the driver.
- **Statement cache.** Up to 32 prepared statements, least recently used
  evicted, keyed by SQLDelight's query identifier. Statements are finalised
  on eviction, on failure and on close.
- **Close.** `PRAGMA wal_checkpoint(TRUNCATE)`, then close. When it is the last
  connection to the file, `-wal` and `-shm` disappear. If another process is
  still reading, the checkpoint gives up after `busy_timeout` without an
  error and the WAL stays. The file is only safe to copy on its own after the
  last connection (app and CLI) has closed.

The fallback if one connection becomes a bottleneck (UI reads blocked behind
sync writes) is in ADR-0002.

## Tests

The shared tests in `commonTest` run on the JVM (counted by the coverage
gate) and on linuxX64 in `./gradlew check`. The JVM also runs `TwoProcessTest`,
which starts a second JVM writing to the same file.

**Android:** the Android artifact of `sqlite-bundled` contains SQLite only for
device ABIs, so Android host tests fail with
`UnsatisfiedLinkError: no sqliteJni`; `testAndroidHostTest` is disabled in this
module. The same `commonTest` suite runs on a device or emulator:

```
./gradlew :core:sqlite:connectedAndroidDeviceTest
```

CI has no emulator, so this is run by hand when the module changes. The Kotlin
code is common to all targets, so the JVM and linuxX64 runs cover it; the
device run checks the Android packaging and native library.
