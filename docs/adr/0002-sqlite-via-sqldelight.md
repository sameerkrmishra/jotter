# 0002. SQLite via SQLDelight with a bundled SQLite build

- **Status:** Proposed (to be accepted once the M0 FTS5 spike passes)
- **Date:** 2026-10-21

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
`core/storage`. SQLDelight generates type-safe Kotlin from them.

Bundle our own SQLite build instead of using the platform one, so FTS5 is
available and behaves identically everywhere. Candidates are the androidx
bundled SQLite driver on Android and xerial `sqlite-jdbc` on the JVM. The exact
driver choice is made by the spike below.

SQLDelight's Android driver does not directly use the `SQLiteDriver` API of
androidx `sqlite-bundled`. The spike must check the adapter path between the
two (an existing adapter or a small one of our own) as well as FTS5 itself.

The spike must also show that FTS5 works on both Android and the JVM, and
that the SQLDelight dialect accepts `CREATE VIRTUAL TABLE ... USING fts5`. If
it fails, the decision is rewritten before acceptance.

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
- Dialect support for FTS5 and the driver adapter are not yet proven. This is
  the main risk, and the reason for the spike.

## Alternatives considered

- **SQLite via Room KMP:** Google-backed and familiar to Android developers.
  Rejected as the default because it is annotation-heavy, hides SQL in DAOs,
  and its desktop support is newer. It is a viable alternative if SQLDelight
  fails the spike.
- **Folder of Markdown files plus an index:** human-readable without the app,
  but needs a separate search index, keeps metadata in front-matter, and makes
  multi-file atomic updates hard. Kept as the export format only.
- **Realm Kotlin:** deprecated by MongoDB, and its sync is a vendor dependency.
- **ObjectBox:** fast, but an opaque binary format with weaker portability.
- **Event log only, with an in-memory model:** simple, but search and queries
  must be rebuilt on every start and there is no single queryable file. The
  sync layer is an event log ([ADR-0004](0004-immutable-change-segments.md)),
  but the live store is not.
