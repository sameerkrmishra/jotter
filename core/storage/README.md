# core:storage

The local store: Jotter's SQLite schema, the revision table and the notes
materialised from it ([PLAN.md](../../docs/PLAN.md) §4,
[ADR-0002](../../docs/adr/0002-sqlite-via-sqldelight.md),
[ADR-0003](../../docs/adr/0003-revision-dag-and-three-way-merge.md)). The
`.sq` files are in `src/commonMain/sqldelight`; SQLDelight generates the
database code from them into the package `...core.storage.db`, which the
coverage gate excludes.

**Dependency rule:** depends on `core:model` (part of its API) and
`core:sqlite`. No sync, merge or backend code: that is M3.

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
- **Text must be valid UTF-16.** SQLite keeps text as UTF-8, which cannot
  encode an unpaired surrogate, and the bundled build is not consistent
  about it: on the JVM a lone surrogate turns into another character (a high
  one swallows the character after it), on Native into U+FFFD. The revision
  store rejects such text, so nothing is stored that does not read back
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
round-trips and that a note's revisions come back in `Stamp` order.

Android host tests are disabled, as in `core:sqlite`: the Android artifact of
`sqlite-bundled` contains SQLite only for device ABIs.
