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
| `HybridLogicalClock` | Issues `HybridTimestamp`s for local events (`now`) and received ones (`receive`) |
| `Stamp` | The one total order of ADR-0003: timestamp, then device id, then revision id |
| `Stamped<T>` | A last-writer-wins value with the stamp of the revision that wrote it |
| `Revision` | One immutable version of a note in its DAG, with `RevisionKind` (user or automatic merge, possibly conflicted) and `RevisionContent` (a full `Snapshot` or a `Tombstone`) |
| `Note` | The current, materialised state of a note that the UI reads |

## Decisions

- **Tags** are trimmed, inner whitespace runs become one space, and they are
  lowercased; 1 to 100 characters, no control characters. Storing one
  spelling keeps tag sets plain sets for the OR-set merge, with no rule needed
  for which spelling wins. Google Keep labels map cleanly because Keep does not
  allow two labels that differ only in case.
- **Revision ids** of both kinds share one hex text form. Hex text order is
  exactly unsigned byte order, so ids compare as strings, and the length tells
  the kind apart.
- **Clock drift:** `HybridLogicalClock.receive` rejects a timestamp more than
  `maxDrift` (default one day) ahead of the wall clock with
  `ClockDriftException` and leaves the clock unchanged. Accepting it would push
  every synced clock into the future for good; clamping it would let a later
  edit sort before the revision it edits. The revision itself can still be
  stored; the caller reports the clock problem.
- **The clock is not persistent or thread-safe.** Its owner stores `last` and
  passes it back on start, and serialises calls (for example in the database
  write transaction).
- `generateV4()` and `generateV7()` from `kotlin.uuid` are still experimental
  in Kotlin 2.4; their opt-ins are confined to the id factories.

## Tests

Unit tests plus property tests (Kotest property, run from `kotlin.test` with
`runTest`) for the HLC's monotonicity and causality and for the total-order
laws of `HybridTimestamp`, `DeviceId`, `RevisionId` and `Stamp`. They run on
the JVM, the Android host and `linuxX64`.
