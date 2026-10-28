# core:storage

The local store that the desktop app, the CLI and later Android use every
day: notes, tags, pin, archive, trash and delete forever, on top of the
revision table ([PLAN.md](../../docs/PLAN.md) §3.3 and §4,
[ADR-0002](../../docs/adr/0002-sqlite-via-sqldelight.md),
[ADR-0003](../../docs/adr/0003-revision-dag-and-three-way-merge.md)). The
`.sq` files are in `src/commonMain/sqldelight`; SQLDelight generates the
database code from them into the package `...core.storage.db`, which the
coverage gate excludes.

```kotlin
val store = NoteStore.open(path, deviceName = "Laptop", queryContext = Dispatchers.IO)
val note = store.notes.create(title = "", body = "- [ ] milk", tags = setOf(TagName.parse("Shopping")))
store.notes.edit(note.id, basedOn = note.revisionId, title = "Groceries", body = "- [x] milk")
store.notes.observeList(NoteFilter(tag = TagName.parse("Shopping"))) // Flow<List<Note>>
// ...
store.close() // checkpoints the WAL
```

**Dependency rule:** depends on `core:model` and kotlinx-coroutines (both part
of its API) and `core:sqlite`. No sync, merge or backend code: that is M3.

## API

| Type | What it is |
|---|---|
| `NoteStore` | Opens or creates the database file and this device's id. The composition root owns it and closes it |
| `NoteService` | `create`, `get`, `list`, `edit`, `addTag`, `removeTag`, `setPinned`, `setState`, `deleteForever`, `history`, `tags`, and the `observe*` flows |
| `NoteFilter` | A state (active, archived or trash) and optionally a tag |
| `TagCount` | A tag with the number of notes outside the trash that have it |
| `NoSuchNoteException` | The note does not exist, or was deleted forever |
| `StaleEditException` | An edit based on a revision that is no longer the current one |

## Schema (version 1)

| Table | What it holds |
|---|---|
| `device` | One row: this device's id and name, and the last timestamp of its hybrid logical clock |
| `revision` | Every revision of every note, user revisions and automatic merges, snapshots and tombstones. Never updated or deleted |
| `revision_parent` | A revision's parents, one row each |
| `revision_tag` | A snapshot's tags, one row each |
| `note` | The current state of every note that is not deleted forever, materialised from its head revision |
| `note_tag` | The current tags of each note. The tag list is a `GROUP BY` over it; there is no `tag` table |

`conflict`, `backend` and `sync_cursor` (PLAN.md §4) come with M3 and M4, as
migrations.

Encoding:

- Ids are stored as their canonical text (`toString()`). That text orders
  like the ids' bytes, so `ORDER BY hlc_physical, hlc_counter, device_id, id`
  is `Stamp` order.
- A `HybridTimestamp` is two INTEGER columns, `*_physical` and `*_counter`.
- `NoteState` is stored by name; booleans as 0 or 1. CHECK constraints
  enforce both, and that a tombstone row has no snapshot columns while a
  snapshot row has all of them.
- A last-writer-wins field (`pinned`, `state`) is stored with its whole
  `Stamp`, because a revision that does not write the field carries its
  parent's stamp (ADR-0003, refinements).
- `revision_parent.parent_id` has no foreign key: in M3 a revision can arrive
  by sync before its parent.
- `note.fts_rowid` exists only because an FTS5 index with external content
  needs an integer key.

## Design

- **Revisions round-trip exactly.** `RevisionStore` (internal) stores every
  shape `Revision` allows and reads it back equal. Inserting is idempotent
  by id, as sync needs; a different revision under a stored id is an error.
- **Materialisation.** `NoteMaterialiser` (internal) writes a note's `note`
  and `note_tag` rows from its head revision, or deletes them for a
  tombstone. `createdAt` is the earliest stored revision's physical time,
  set when the row is inserted and never changed; `updatedAt` is the head's
  timestamp. In M1 every note has exactly one head, because a user revision
  always builds on the current head in the same transaction. Finding heads
  and merging them is M3.
- **Every write is one transaction.** `NoteService` reads the device row,
  builds a `HybridLogicalClock` from its stored timestamp, writes a user
  revision on top of the note's current one, materialises it and stores the
  clock's new timestamp, all in one `BEGIN IMMEDIATE` transaction. So the
  desktop app and the CLI serialise their writes, and timestamps increase
  strictly across restarts and processes even if the wall clock goes back.
  Reads use no transaction (see `core:sqlite`).
- **Last-writer-wins stamps.** `create`, `setPinned` and `setState` stamp
  the field they write with the new revision's stamp. Every other revision
  carries the parent's stamp over unchanged (ADR-0003, refinements).
- **No-op writes** (the same title and body, a tag the note has, the current
  pin or state) write no revision and return the note unchanged.
- **Stale edits.** `edit` takes the revision the editor started from and
  fails with `StaleEditException` if the note has moved on, checked inside
  the write transaction. Without merging (M3), saving would drop the other
  change. Tag, pin and state changes apply to the current revision: they
  touch one item and cannot lose concurrent text.
- **Delete forever** is allowed only from the trash. It writes a tombstone
  and removes the `note` row; `history` still returns every revision.
- **List order:** pinned first, then `updatedAt` descending, then note id,
  so it is total and stable.
- **Flows** use SQLDelight's `asFlow()` and run their queries on the
  `queryContext` passed to `open`. They re-run after every write made
  through the same `NoteStore`, once per transaction. Writes from another
  process (the CLI while the app runs) do not notify them; the app has to
  refresh, for example when its window gains focus.
- **Blocking API.** Every function blocks on SQLite I/O; callers move it off
  the UI thread. It may be called from any thread.
- **Text must be valid UTF-16.** SQLite keeps text as UTF-8, which cannot
  encode an unpaired surrogate, and the bundled build is not consistent
  about it: on the JVM a lone surrogate turns into another character (a high
  one swallows the character after it), on Native into U+FFFD. `NoteService`
  replaces unpaired surrogates in titles and bodies with U+FFFD, and the
  revision store rejects them, so nothing is stored that does not read back
  equal.

## Schema changes

Schema version 1 is frozen once M1 closes. From then on every change is a
migration: `1.sqm` turns version 1 into 2, and so on, next to the `.sq`
files.

`verifyMigrations` is on: `check` applies the migrations to every saved
schema in `src/commonMain/sqldelight/databases` (`1.db`, ...) and fails if the
result differs from the schema the `.sq` files create. So a schema change
without a migration fails the build. While M1 is open, schema 1 may still
change; regenerate `1.db` after editing a `CREATE` statement:

```
./gradlew :core:storage:generateCommonMainJotterDatabaseSchema
```

After adding a migration, run the same task to save the new version as well
(`2.db`, and so on).

## Tests

Shared tests in `commonTest` run on the JVM (counted by the coverage gate) and
on linuxX64. Kotest property tests check that every shape of revision
round-trips, that a note's revisions come back in `Stamp` order, and that
timestamps increase whatever the wall clock does. `ClockTest` also opens two
stores on one file, as the app and the CLI do, and writes from both, in turn
and concurrently.

Android host tests are disabled, as in `core:sqlite`: the Android artifact of
`sqlite-bundled` contains SQLite only for device ABIs.
