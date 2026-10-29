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
| `NoteService` | `create`, `get`, `list`, `edit`, `addTag`, `removeTag`, `setPinned`, `setState`, `deleteForever`, `history`, `tags`, `search`, and the `observe*` flows |
| `NoteFilter` | A state (active, archived or trash) and optionally a tag |
| `TagCount` | A tag with the number of notes outside the trash that have it |
| `SearchHit`, `Highlighted` | A search result: the note and a snippet with its match ranges |
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
| `note_fts` | FTS5 index over `note.title` and `note.body` (external content), kept in step by triggers on `note` |

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
- `revision` is an ordinary rowid table (its rows hold whole bodies, which a
  WITHOUT ROWID table stores larger and reads slower); the small edge tables
  are WITHOUT ROWID. `revision_by_note` indexes
  `(note_id, hlc_physical, hlc_counter, device_id, id)`, which serves both
  `history` in `Stamp` order and the `createdAt` minimum.
- `note.fts_rowid` exists only because an FTS5 index with external content
  needs an integer key.

## Design

- **Revisions round-trip exactly.** `RevisionStore` (internal) stores every
  shape `Revision` allows and reads it back equal. Inserting is idempotent
  by id, as sync needs; a different revision under a stored id is an error.
- **Materialisation.** `NoteMaterialiser` (internal) writes a note's `note`
  and `note_tag` rows from its head revision, or deletes them for a
  tombstone. `createdAt` is the earliest stored revision's physical time,
  recomputed on every write from an index (in M3 an ancestor may arrive
  after its descendants, so it can move back); `updatedAt` is the head's
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
  builds on the note's current revision. Inside the write transaction it
  fails with `StaleEditException` if that revision is not one of the note's
  or its title or body differ from the current ones: without merging (M3),
  saving would drop the other edit. A tag, pin or state change in between is
  fine and is kept. Tag, pin and state changes themselves apply to the
  current revision: they touch one item and cannot lose concurrent text.
- **Delete forever** is allowed only from the trash. It writes a tombstone
  and removes the `note` row; `history` still returns every revision.
- **List order:** pinned first, then `updatedAt` descending, then note id,
  so it is total and stable.
- **Flows** use SQLDelight's `asFlow()` and run their queries on the
  `queryContext` passed to `open`. A query re-runs after every write
  transaction through the same `NoteStore` that touches its tables, even if
  its result does not change; `distinctUntilChanged()` drops equal results.
  Emissions are conflated, so a slow collector sees only the latest result.
  Writes from another process (the CLI while the app runs) do not notify
  them; the app has to refresh, for example when its window gains focus.
- **Blocking API.** Every function blocks on SQLite I/O; callers move it off
  the UI thread. It may be called from any thread.
- **Text must be storable.** SQLite keeps text as UTF-8, which cannot
  encode an unpaired surrogate, and the bundled build is not consistent
  about it: on the JVM a lone surrogate turns into another character (a high
  one swallows the character after it), on Native into U+FFFD. U+FFFE and
  U+FFFF come back as U+FFFD on both. A U+FEFF that starts the text is
  dropped as a byte order mark on the JVM and becomes U+FFFD on Native, and
  on Native text ends at the first NUL. `NoteService` replaces all of these
  in titles and bodies with U+FFFD, the revision store rejects them, and
  `TagName` does not allow them, so nothing is stored that does not read
  back equal on every platform. U+FEFF elsewhere round-trips.

## Search

`search(query, filter, limit = 100)` returns the best hits, ranked by
`bm25(note_fts, 10.0, 1.0)` (the title weighs ten times the body), ties in
list order, filtered by state and tag like `list`.

- **User text is never FTS5 syntax.** The query is split at whitespace
  (`TagName.WHITESPACE`, and NUL); each word becomes a quoted string with `"`
  doubled, the words are ANDed, and the last one is a prefix (`"wor"*`), so
  results follow typing. A query without words gives no hits, and no input
  throws (a property test feeds arbitrary strings, FTS5 operators, quotes,
  emoji, NUL and lone surrogates). NUL must be a separator: FTS5 reads the
  query as a C string, so a NUL inside a quoted word is a syntax error.
- **Tokenizer:** `unicode61 remove_diacritics 2`, so case and accents fold
  (`cafe` finds `Café`). `prefix = '2 3'` adds prefix indexes, because every
  query ends in a prefix and a short prefix otherwise merges the posting
  lists of every word starting with it. It roughly doubles the index (15 MB
  to 32 MB for the benchmark's 10,000 notes).
- **Snippets.** `highlight()` (title) and `snippet()` (body, 24 tokens) mark
  matches with U+E000/U+E001, which `parseHighlighted` removes, returning
  ranges into the clean text. The body excerpt is used if the body matched,
  otherwise the title. Limitation: a note's own U+E000/U+E001 characters
  cannot be told from the markers. They are dropped from its snippet and may
  move or drop highlights there, but ranges stay valid (in bounds, ordered,
  non-empty).
- **Two-step query.** A CTE ranks every match using only the index and the
  short `note` columns, takes the best `limit`, and only those get a
  snippet. `snippet()` on every match costs far more (see "all hits" below).
  For the same reason `title` and `body` are the last columns of `note`: a
  long body spills into overflow pages that ranking then does not read.

### SQLDelight FTS5 workarounds (2.4.1, [spike 0001](../../docs/spikes/0001-fts5-sqldelight.md))

- An unaliased FTS5 `rowid` (or `rank`) result column crashes code
  generation: the queries select `note.fts_rowid` instead.
- FTS5 commands (`INSERT INTO note_fts(note_fts) VALUES (...)`) crash
  generation as named queries; inside triggers they are fine. Run them
  through the driver (the tests' `integrity-check` does).
- On a join, `MATCH` and auxiliary functions need the table name, and a
  nested subquery that repeats `note` or `note_fts` is rejected ("multiple
  columns found"); SQLDelight also does not resolve an alias of the FTS
  table in `MATCH`. The search query therefore ranks in a CTE.
- The plugin links Native test binaries against the system `libsqlite3` by
  default; `linkSqlite = false`, since `core:sqlite` brings its own.

### Benchmark (PLAN.md §10: < 50 ms median over 10,000 notes)

`./gradlew :core:storage:searchBenchmark` (not part of `check`: CI timings
are noisy) generates 10,000 notes with a fixed seed: 10 to 100 Markdown
lines each, words drawn from a Zipf distribution (common English words, then
note words, then a long tail of made-up words), 0 to 3 of 10 tags. It then
runs each query 20 times to warm up and measures 100 runs, through
`NoteService.search` on one thread.

Measured 2026-10-29 on an Intel Xeon W-2135 @ 3.70 GHz (6 cores, 12
threads), Linux 6.18, OpenJDK 21.0.12:

| Query | Text | Hits | Median | p95 | Median, all hits | p95, all hits |
|---|---|---|---|---|---|---|
| common word | `the` | 10000 | 39.2 ms | 40.2 ms | 612.2 ms | 615.9 ms |
| rare word | `memesu` | 36 | 1.4 ms | 1.5 ms | 1.4 ms | 1.4 ms |
| 3-letter prefix | `pro` | 8916 | 27.9 ms | 28.7 ms | 261.0 ms | 266.4 ms |
| 1-letter prefix | `t` | 10000 | 109.8 ms | 110.5 ms | 1234.5 ms | 1242.8 ms |
| two words | `meeting project` | 1797 | 10.5 ms | 10.7 ms | 62.6 ms | 63.1 ms |
| word + tag filter | `project` in `work` | 579 | 16.0 ms | 16.3 ms | 30.0 ms | 30.2 ms |

"Median" and "p95" use the default limit of 100 hits; "all hits" passes no
effective limit, which computes a snippet for every match. All five PLAN.md
queries meet the target. `the` is the worst case: it is in every note and,
as a prefix, also matches `then`, `they`, `there` and so on; without the
prefix index it took about 52 ms. The 1-letter prefix (the first keystroke of
search as you type, not a PLAN.md query) is the slowest; the UI may want to
wait for a second character on large collections.

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
round-trips, that a note's revisions come back in `Stamp` order, that
timestamps increase whatever the wall clock does, and that no search query
throws. FTS5's `integrity-check` verifies the index against `note` after
random edits and deletions. `ClockTest` also opens two stores on one file, as
the app and the CLI do, and writes from both, in turn and concurrently.

Android host tests are disabled, as in `core:sqlite`: the Android artifact of
`sqlite-bundled` contains SQLite only for device ABIs.
