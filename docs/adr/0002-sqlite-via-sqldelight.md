# 0002. SQLite via SQLDelight with a bundled SQLite build

- **Status:** Accepted
- **Date:** 2026-10-21 (accepted 2026-10-22 after
  [spike 0001](../spikes/0001-fts5-sqldelight.md))
- **Changed since Proposed:** androidx `sqlite-bundled` on the JVM too (instead
  of xerial `sqlite-jdbc`), our own driver adapter (instead of an open choice),
  dialect pinned to `sqlite-3-44`, and multi-process access rules added.

## Context

Each device keeps a full local replica ([ADR-0001](0001-local-first-architecture.md)).
The store must:

- be a single SQLite file the user can copy to a flash drive and open
  elsewhere;
- support full-text search over title and body;
- run on Android and on the JVM (Linux desktop and CLI) from shared Kotlin code;
- be readable and reviewable, since correctness matters more than speed.

Notes are small (10–100 lines). Even thousands of notes are a few MB, so any
option is fast enough ([PLAN.md §2](../PLAN.md#2-storage-layer-options)).

Android's system SQLite cannot be relied on for FTS5. On the API 26 and API 36
default emulator images it has no FTS5 module at all
([spike 0001](../spikes/0001-fts5-sqldelight.md)). Relying on it would make
search behave differently per device.

The desktop app and the CLI can open the same database file at the same time
([PLAN.md §3](../PLAN.md#3-architecture)), so the store must handle more than
one process.

## Decision

Use SQLite through SQLDelight. Schema and queries live in `.sq` files in
`core/storage`. SQLDelight generates type-safe Kotlin from them, using the
`app.cash.sqldelight:sqlite-3-44-dialect`.

Bundle our own SQLite build instead of using the platform one, so FTS5 is
available and behaves identically everywhere. Use androidx `sqlite-bundled`
(`BundledSQLiteDriver`) on **both** Android and the JVM, so every device runs
the same SQLite build.

[Spike 0001](../spikes/0001-fts5-sqldelight.md) proved the FTS5 part: the
dialect accepts `CREATE VIRTUAL TABLE ... USING fts5` with `MATCH`, `bm25()`,
`rank`, `snippet()` and `highlight()`, and the same FTS5 tests pass on the JVM
and on Android emulators at API 26 and API 36. The platform SQLite failed with
`no such module: fts5` on both API levels. The adapter and multi-process rules
below are not proven yet; M1 tests them.

SQLDelight has no driver for the androidx `SQLiteDriver` API, so `core/sqlite`
(its own small module, so `core/storage` stays focused on schema and
repositories) contains our own synchronous `SqlDriver` adapter over androidx
`SQLiteConnection`, with synchronous code generation (`generateAsync = false`).
The spike version is 109 lines and is not production-ready: it shares one
connection with no lock, and its listener map is not synchronised. The M1
adapter must cover, each with tests:

- thread safety: one connection behind a mutex, or confined to one thread;
- nested transactions, including rollback of the outer transaction when an
  inner one fails, and `afterCommit`/`afterRollback` callbacks;
- listener notifications, so `asFlow()` queries re-run after writes;
- a prepared-statement cache keyed by the SQLDelight query identifier;
- connection pragmas on open: `journal_mode = WAL`, `busy_timeout`,
  `foreign_keys = ON`;
- a clean close that checkpoints the WAL into the main file, for example
  `PRAGMA wal_checkpoint(TRUNCATE)` before closing the last connection, so no
  `-wal`/`-shm` files are left behind. This assumes no persistent WAL
  (`SQLITE_FCNTL_PERSIST_WAL` unset, the androidx bundled build's default).
  SQLite only removes the WAL when the last connection across all processes
  closes. The M1 test verifies that `-wal`/`-shm` are gone after close.

Expected cost: a few hundred lines with tests, plus keeping up with
`SqlDriver` and `SQLiteConnection` API changes. Fallback trigger: if M1 or a
later milestone needs more than one connection per process, we stop extending
the adapter. The observable signal is UI reads measurably blocked behind sync
writes (for example more than 100 ms), which concurrent readers would fix. We
then switch to `com.eygraber:sqldelight-androidx-driver`, which already pools
connections. Until then every transaction, read-only ones included, uses
`BEGIN IMMEDIATE` and holds the write lock, so pure reads stay outside
transactions.
That switch is **not** drop-in: the eygraber driver only accepts an async
schema, so it needs `generateAsync = true`, and every generated query and
every call site in `core/storage` becomes `suspend`. It is a migration to plan,
so the decision to switch must be made early.

Multi-process access: every connection opens in WAL mode with a
`busy_timeout` (5 s to start with), and write transactions use
`BEGIN IMMEDIATE`. A second writer then waits instead of failing at once with
`SQLITE_BUSY`. M1 adds a test with two connections, and ideally two
processes, writing to the same file at the same time.

WAL relies on shared memory between processes, which is unsafe on network
file systems (NFS, SMB). The live database must therefore be on a local file
system.

Copying: the database is a single SQLite file. Copying it is safe when the app
and the CLI are not running: on a clean close SQLite checkpoints the WAL into
the main file and removes the `-wal` and `-shm` files. While the app is
running, the user uses "Export database", which writes a consistent
single-file copy (`VACUUM INTO`). If the app or CLI crashed or was killed
(common on Android), committed data may still be in the `-wal` file. Open and
close the app once before copying (SQLite recovers the WAL on open), or use
"Export database". This rule exists because of the `-wal` file, not because of
the flash drive.

## Consequences

### Positive

- One portable SQLite file meets the flash-drive requirement: copy it when
  the app and CLI are not running, or use "Export database" while they run.
- FTS5 gives fast, ranked full-text search without an external search engine.
- SQL is visible in `.sq` files, so reviewers can read every query.
- Generated code is type-safe, and SQLite is mature on both targets.
- Identical SQLite version on all devices removes a class of
  platform-specific bugs.

### Negative

- Bundling SQLite adds a native library to the APK and desktop builds and
  increases their size.
- We take on keeping the bundled SQLite up to date, including security fixes,
  instead of getting them from the OS.
- Generated code must be excluded from the coverage gate.
- SQLDelight 2.4.1 has two FTS5 codegen bugs: it crashes on unaliased FTS5
  `rowid` or `rank` result columns, and on labeled FTS5 command statements
  (`INSERT INTO t(t) VALUES ('rebuild')`). We alias those columns and run FTS5
  commands through the driver. One more gotcha, which is a correct error: a
  `MATCH` column must be qualified on joins (see the spike).
- We own, test and maintain the driver adapter (scope above).
- The bundled SQLite follows androidx releases, which can lag upstream SQLite.
- FTS5 query syntax is visible to users: search text must be escaped, because a
  malformed `MATCH` expression is an SQL error.

## Alternatives considered

- **SQLite via Room KMP:** Google-backed and familiar to Android developers.
  Rejected as the default because it is annotation-heavy, hides SQL in DAOs,
  and its desktop support is newer. It would not help with FTS5 either: Room
  only models FTS3/FTS4 tables, so FTS5 would still be raw SQL.
- **Folder of Markdown files plus an index:** human-readable without the app,
  but needs a separate search index, keeps metadata in front-matter, and makes
  multi-file atomic updates hard. Kept as the export format only.
- **Realm Kotlin:** deprecated by MongoDB, and its sync is a vendor dependency.
- **ObjectBox:** fast, but an opaque binary format with weaker portability.
- **requery `sqlite-android` with SQLDelight's Android driver:** works, but
  its last release is from May 2025 and it is only published on JitPack.
- **The platform SQLite:** has no FTS5 module on the API levels tested.
- **xerial `sqlite-jdbc` on the JVM:** has FTS5 and works, but is a different
  SQLite build from Android's. It remains fine for JVM-only tools.
- **Event log only, with an in-memory model:** simple, but search and queries
  must be rebuilt on every start and there is no single queryable file. The
  sync layer is an event log ([ADR-0004](0004-immutable-change-segments.md)),
  but the live store is not.
