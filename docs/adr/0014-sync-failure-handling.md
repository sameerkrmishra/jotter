# 0014. Gaps, repairs and limits in the sync cycle

- **Status:** Proposed
- **Date:** 2026-11-02

## Context

[ADR-0004](0004-immutable-change-segments.md) defines the cycle and leaves open
what happens around a failure. Backends lie by omission: a segment can be late, can
be lost to a power cut after it was published
([ADR-0012](0012-local-folder-publishing.md)), or can be damaged or written by a
newer version. A failed `writeNew`, a failed retry included, may have published
the file. One bad file must not poison a device or block the others.

## Decision

**Local tables** (schema 2): `sync_cursor` and `sync_gap` per backend and device,
`sync_segment` for this device's own segments, `backend_revision` for what is known
to be on a backend, `conflict` as PLAN.md §4 says. `backend_id` is opaque, as the
`backend` table comes with M4.

**A cursor and its gaps.** The cursor is the highest number processed in a folder,
and every number at or below it that was not applied is a row in `sync_gap`: a
number the listing skipped, a damaged, rejected or future-format file, or a file
that could not be read. Gaps are tried again on every cycle. So a bad file costs one
read per cycle, blocks nothing behind it, and applies by itself once it is repaired
or an update can read it. A folder may skip at most 4096 numbers at once, so a
file named `999999999999.json.gz` does not cost a trillion rows; beyond that the
folder stops there, with `ImplausibleSegmentNumber`, and other folders go on.

**What moves a cursor.** Only a transaction that applied or recorded the numbers
it passes. `list` errors, and any `BackendUnavailableException`, end the cycle as
`Interrupted`: what was already read is applied, and nothing else moves. An empty
listing from a reachable store is believed, as `BlobStore` promises, and costs
nothing: the numbers it lacks stay ahead of the cursor, and this device's own
segments are rewritten, which `writeNew` refuses harmlessly if they were there.

**Publishing.** A segment is recorded in one transaction with its number and
revision ids, before `writeNew`. From then on it does not change. Any failure leaves
it recorded, and the next cycle writes the same revisions under the same number.
Only a success, or `BlobAlreadyExistsException` with the same decoded content at the
path, marks it published. Other content there is `SegmentCollision`, as ADR-0004
says: the device stops pushing to that backend and goes on pulling. A segment
that cannot be decoded at our path counts as other content.

**Repair.** The cycle lists its own folder, and a published segment missing from it
is written again with the same number and revisions, which fills the reader's gap.
This answers the "hole" ADR-0012 leaves to M3.

**A revision on arrival.** Decoding checks the format, every field, that text can be
stored, and each merge's id against its hash. Before anything is written, a segment
is also checked against the database, from both ends of every edge, since a child
may come first: the same id cannot have other content, a parent is of the same note,
a user revision is stamped above its parents, a merge not below them, and a merge
with both parents known has the stamp and loser they decide. These rules also keep
cycles out: a cycle needs a child stamped below its parent. A failure rejects the
whole segment and leaves no trace.

**Waiting for ancestors.** A note with a parent that has not arrived is shown, but
not merged: a merge reads the ancestors, so without them two devices would compute
different text. It goes on when the parent arrives.

**Limits.** A file, compressed or inflated, is at most 32 MiB. Inflating stops at
the limit, so a small file cannot fill memory. A push splits revisions so that each
segment fits for certain, counting six bytes of JSON per UTF-16 unit, which is the
worst case. So a revision of more than about 5 million characters fits in no segment;
it stays on the device with `RevisionTooLarge`, and the note waits on other devices
for what builds on it. A pull applies once it holds about 32 MiB, in one transaction
per batch.

## Consequences

### Positive

- A crash at any point repeats work at most. The simulator crashes at every step.
- A bad file is visible, in `SyncReport.issues`, and has no effect on anything else.
- A segment lost on a backend comes back, while its writer lives.

### Negative

- A corrupt file stays a gap for good, one read per cycle.
- A segment lost on a backend after its writer's database is gone stays lost there.
  Other devices believe its revisions are on that backend (`backend_revision`), and
  do not push them again. A new device on it alone would miss them.
- ADR-0004's collision has no way out yet. The user can only remove the device's
  backend. Giving a copied database a new device id is still open.
- The checks cannot prove that a merge's text is right: its hash shows only that
  nothing changed since it was made. A buggy device that hashes its wrong text is
  believed.
- Every cycle takes a write transaction, even with nothing to apply, to settle notes
  with several heads.

## Alternatives considered

- **Stop a folder at the first bad segment:** the simplest cursor, but one damaged
  file blocks a device's later revisions for good, against the task of this ADR.
- **A set of applied numbers instead of a cursor:** no gaps table, but it grows with
  the log, where gaps are rare.
- **Rewriting a lost segment's revisions under a new number:** works, but readers
  keep a gap for the old one for good, and ADR-0004 says numbers are not reused or
  skipped.
- **Re-deriving every received merge:** would catch wrong text, at the cost of a full
  merge per received merge, and needs a policy for what then happens to a merge
  other devices have built on.
