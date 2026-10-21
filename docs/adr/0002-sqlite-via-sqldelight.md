# 0002. SQLite via SQLDelight with a bundled SQLite build

- **Status:** Accepted
- **Date:** 2026-10-21 (accepted 2026-10-22 after
  [spike 0001](../spikes/0001-fts5-sqldelight.md))

## Context

Each device keeps a full local replica ([ADR-0001](0001-local-first-architecture.md)).
The store must:

- be a single file the user can copy to a flash drive and open elsewhere;
- support full-text search over title and body;
- run on Android and on the JVM (Linux desktop and CLI) from shared Kotlin code;
- be readable and reviewable, since correctness matters more than speed.

Notes are small (10–100 lines). Even thousands of notes are a few MB, so any
option is fast enough ([PLAN.md §2](../PLAN.md#2-storage-layer-options)).

Android's system SQLite does not reliably ship the FTS5 extension on any
version. Relying on it would make search behave differently per device.

## Decision

Use SQLite through SQLDelight. Schema and queries live in `.sq` files in
`core/storage`. SQLDelight generates type-safe Kotlin from them, using the
`app.cash.sqldelight:sqlite-3-44-dialect`.

Bundle our own SQLite build instead of using the platform one, so FTS5 is
available and behaves identically everywhere. Use androidx `sqlite-bundled`
(`BundledSQLiteDriver`) on **both** Android and the JVM, so every device runs
the same SQLite build.

SQLDelight has no driver for the androidx `SQLiteDriver` API, so `core/storage`
contains a small synchronous `SqlDriver` adapter over androidx
`SQLiteConnection`. If the adapter grows beyond a small class, switch to the
maintained third-party `com.eygraber:sqldelight-androidx-driver`, which needs
`generateAsync = true`.

[Spike 0001](../spikes/0001-fts5-sqldelight.md) proved this: the dialect
accepts `CREATE VIRTUAL TABLE ... USING fts5` with `MATCH`, `bm25()`, `rank`,
`snippet()` and `highlight()`, and the same FTS5 tests pass on the JVM and on
Android emulators at API 26 and API 36. The platform SQLite failed with
`no such module: fts5` on both API levels.

## Consequences

### Positive

- One portable file meets the flash-drive requirement.
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
- SQLDelight 2.4.1 fails to generate code for unaliased FTS5 `rowid` or
  `rank` result columns, and for labeled FTS5 command statements
  (`INSERT INTO t(t) VALUES ('rebuild')`). We alias those columns and run FTS5
  commands through the driver (see the spike).
- We own and test the driver adapter (transactions, listeners, threading).
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
