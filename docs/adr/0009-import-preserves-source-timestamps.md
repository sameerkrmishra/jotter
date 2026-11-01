# 0009. Imported notes keep their source timestamps as revision stamps

- **Status:** Accepted
- **Date:** 2026-11-01

## Context

The Keep import of milestone M2 has to preserve a note's timestamps and be
idempotent ([PLAN.md §7](../PLAN.md#7-interop)). But `Note.createdAt` and
`updatedAt` are derived from a note's revisions, and
[ADR-0003](0003-revision-dag-and-three-way-merge.md) gives every user revision
a fresh HLC reading, which would date every imported note to the import. This
ADR amends ADR-0003 with one exception. It is numbered 0009 because 0007 and
0008 are reserved for the M6 encryption and multi-user plans.

A revision id cannot be derived from the source note: two devices that import
the same note write different device ids, so the same revision id would stand
for different content, which `RevisionStore.insert` rejects.

## Decision

- **Note id.** Only the `NoteId` is deterministic, derived from the source
  note. Exactly which source fields is for the M2 importer, but they must not
  change between Takeouts.
- **Revision ids.** Revision ids stay ordinary UUIDv7 with the importing
  device's id, so two devices importing the same note produce distinct
  revisions and two roots.
- **Stamps.** The physical time is the source time truncated to whole
  milliseconds, and the counter is 0.
- **Revisions.** A root at the created time, and a second revision at the
  edited time only if it is strictly later than the created time in
  milliseconds, so the child is always strictly above its parent.
- **The clock.** This is the one exception to "fresh clock reading". The
  device's clock is not moved back. Later edits use `now(after = parent)`.
- **Re-import.** A note with any history, including a tombstone, is skipped.
- **Several roots.** M3 must merge two parents that have no common ancestor,
  which happens only when a note has several roots. Then, for each of `body`
  and `title` separately, if all roots among the ancestors of the two parents
  being merged have the same value, that value is the base; otherwise the base
  is empty. The rule depends only on the two parents, as ADR-0003 requires of
  an automatic merge, so devices that know of different roots still agree. A
  parent counts as its own ancestor, so a root that is itself a parent is one
  of these roots. An
  empty base alone would conflict on all the text as soon as one device edits
  its copy, although the other changed nothing. Two parents with a common
  ancestor use it as usual, so a note with one root is unaffected.

## Consequences

### Positive

- `createdAt`, `updatedAt` and list order show the source times with no new
  column, and revisions keep one shape.
- Re-importing on one device duplicates nothing and does not bring back a note
  that was deleted forever.
- The same note imported on two devices ends up as one note, because the note
  id is the same.

### Negative

- Accepted limitation: if device A deletes a note forever and device B imports
  it before syncing, delete-vs-edit brings it back in the trash. Nothing is
  lost, and the "tombstone is skipped" guarantee is local to a device.
- A re-import silently skips newer Keep edits of a note already imported.
- Roots with different values, for example from two Takeouts taken apart with
  an edit in Keep in between, merge against an empty base and may conflict on
  the whole `body` or `title`.
- The several-roots base covers only `body` and `title`. Tags have no such
  base, so in M3 a tag removed on one device comes back from the other root,
  because tags merge as an OR-set and an add wins.
- `merge` code has a case that only imports create: several roots of one note.
  Two devices importing one note also keep two extra revisions in its history
  for good.
- A pin or state written by an import carries the old stamp, so any real later
  change beats it, and two imports tie-break on device id.
- A source time ahead of the wall clock stays in that note's revisions, and
  later edits go above it, as with a far-future parent in ADR-0003.

## Alternatives considered

- **Fresh stamps with separate display-time columns:** keeps ADR-0003 without
  an exception, but is a breaking change to the schema and to `Snapshot`, and
  every revision would carry times that mean something only for imports.
- **Deterministic revision ids:** avoids two roots, but two devices write
  different device ids under one id, which `RevisionStore.insert` rejects as
  the same id with different content. Dropping the device id from the identity
  would make imports a third kind of revision.
- **Moving the clock back for the import:** would break "timestamps increase
  strictly" for everything written alongside the import.
