# core:model

The domain types of Jotter: notes, revisions, tags, ids and the hybrid logical
clock (see [docs/PLAN.md](../../docs/PLAN.md) §4 and §5, and
[ADR-0003](../../docs/adr/0003-revision-dag-and-three-way-merge.md)). Plain
immutable data plus the clock; the merge rules themselves live in `core/merge`
and `core/sync`.

**Dependency rule:** pure Kotlin. No I/O, no platform APIs and no dependencies
on other Jotter modules. Every other module may depend on this one, so it must
stay small, stable and fully tested.

## Types

| Type | What it is |
|---|---|
| `NoteId` | A note's identity. New ids are UUIDv7 |
| `DeviceId` | One installation's identity, a random UUID (v4). Ordered as unsigned bytes |
| `RevisionId` | A revision's identity: UUIDv7 for user revisions, a 32-byte content hash for automatic merges. Text form is lowercase hex without dashes; ordered as unsigned bytes |
| `TagName` | A normalised tag (rules below) |
| `NoteState` | `ACTIVE`, `ARCHIVED` or `TRASHED`. "Delete forever" is a tombstone revision, not a state |
| `HybridTimestamp` | Wall-clock millis plus a logical counter |
| `HybridLogicalClock` | Issues `HybridTimestamp`s for local events (`now`, optionally `after` a parent's timestamp) and records remote ones (`observe`) |
| `Stamp` | The one total order of ADR-0003: timestamp, then device id, then revision id |
| `Stamped<T>` | A last-writer-wins value with the stamp of the revision that wrote it |
| `Revision` | One immutable version of a note in its DAG, with `RevisionKind` (user or automatic merge, possibly conflicted) and `RevisionContent` (a full `Snapshot` or a `Tombstone`) |
| `Note` | The current, materialised state of a note that the UI reads |

## Decisions

- **Tags** keep their case and compare exactly. Leading and trailing
  whitespace is trimmed and inner runs become one space, using a fixed list of
  Unicode `White_Space` characters; 1 to 100 characters, no control (Cc)
  characters, unpaired surrogates, U+FFFE/U+FFFF or a leading U+FEFF
  (SQLite cannot store them unchanged). Nothing depends on platform Unicode tables (no case folding, no
  `Char.isWhitespace`), so every device derives the same tag sets and merge
  hashes. NFC is not applied (accepted risk). Decoders use
  `TagName.fromNormalised`, which rejects text that is not already normalised
  instead of rewriting it. Google Keep labels map one to one.
- **Revision ids** of both kinds share one hex text form. Hex text order is
  exactly unsigned byte order, so ids compare as strings, and the length tells
  the kind apart. A user revision id is therefore written without dashes,
  while a `NoteId` or `DeviceId` keeps the dashed UUID form; this is
  intentional.
- **Clock drift:** `HybridLogicalClock.observe` rejects a timestamp more than
  `maxDrift` (default one day) ahead of the wall clock with
  `ClockDriftException` and leaves the clock unchanged, so one wrong clock
  cannot push every synced clock into the future. The revision is still
  stored. An edit on top of it uses `now(after = parentTimestamp)`, which is
  always above the parent without moving the clock that far.
- **The clock is not persistent or thread-safe.** The app and the CLI share
  one database and device id, so `last` is read and written inside the write
  transaction that stores the stamped revisions; the simplest way is a clock
  per write transaction, built from the persisted value.
- `generateV4()` and `generateV7()` from `kotlin.uuid` are still experimental
  in Kotlin 2.4; their opt-ins are confined to the id factories.

## Tests

Unit tests plus property tests (Kotest property, run from `kotlin.test` with
`runTest`) for the HLC's monotonicity and causality and for the total-order
laws of `HybridTimestamp`, `DeviceId`, `RevisionId` and `Stamp`. They run on
the JVM, the Android host and `linuxX64`.
