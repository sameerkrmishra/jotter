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
`sync_segment` and `sync_last_number` for this device's own segments,
`backend_revision` for what is known to be on a backend, `conflict` as PLAN.md §4 says,
and `hidden_head`. `backend_id` is opaque, as the `backend` table comes with M4.

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

**Numbers.** A new segment is numbered above both the highest number this device ever
used on the backend and the highest in its folder. So a backend that was forgotten
and added again, whose files stay (PLAN.md §6.5), causes no collision. `sync_last_number`
survives forgetting, as a number whose file was lost may be behind a reader's cursor,
and new content under it would never be read. The simulator found that case.

**Repair.** The cycle lists its own folder, and a published segment missing from it
is written again with the same number and revisions, which fills the reader's gap.
This answers the "hole" ADR-0012 leaves to M3. A segment that was read from another
device and is gone from the backend makes its revisions count as not on the backend
again, so the reader pushes them under its own name. That covers a dead writer; with a
live one it costs a duplicate. `MISSING` is reported on every cycle, not once.

**A revision on arrival.** Decoding checks the format, every field, that text can be
stored, and each merge's id against its hash. Before anything is written, a segment
is also checked against the database, from both ends of every edge, as a user
revision may come before its parent: the same id cannot have other content, a parent
is of the same note, and a user revision is stamped above its parents. A failure
rejects the whole segment and leaves no trace. These rules also keep cycles out: a
cycle needs a child stamped below its parent.

**Merges wait for their parents.** A merge goes in only with both parents here, also
from a later segment of the batch. Its note, stamp and loser are then judged against
them, one merge at a time, so a forged merge is refused whatever arrived first, and
devices keep the same revisions. The rest of its segment goes in, and the segment stays
a gap with `WAITING` or `REJECTED`, to be tried again.

**Hidden heads.** A note with a parent that has not arrived is shown, but not merged:
a merge reads the ancestors, so without them two devices would compute different
text. Merging on what is known would be safe only if nobody ever held the rest, which
no device can tell, so that stays forbidden. The rule instead: **an edit is never hidden
in silence.** Every live head with text that the note does not show is a row in
`hidden_head`, with its text and the reason, `MISSING_ANCESTOR` or `OPEN_CONFLICT`. The
row is rebuilt from the revisions, so it ends only when the head has merged, or when
the user has dealt with it. `ConflictResolver.settleHidden` writes a user revision on
the shown head, whose `resolves` names the hidden head, which then stops counting on
every device. If the ancestor still comes, nothing changes: the user has decided, and
the settled head stays in the DAG. `resolves` may therefore name any revision.

**Limits.** A file, compressed or inflated, is at most 32 MiB. Inflating stops at
the limit, so a small file cannot fill memory. A push splits revisions so that each
segment fits for certain, counting six bytes of JSON per UTF-16 unit, which is the
worst case. A pull applies once it holds about 32 MiB, in one transaction per batch.

**The size of a note.** `MAX_NOTE_CHARS`, 5,000,000 UTF-16 units of title and body, sits
below the roughly 5.5 million that fit a segment. `create` and `edit` fail with
`NoteTooLargeException`, and a resolution likewise, so editing cannot make a note that
can never sync. An import over it writes nothing and answers `TOO_LARGE`, and the Keep
import reports `NOTE_TOO_LARGE` and goes on. A merge past it is a conflict (ADR-0013).
A note already over it stays readable, its tags, pin and state still change, and its
text can only be saved under the cap. Its revisions stay on the device with
`RevisionTooLarge`, the push goes on with everything else, and what later builds on them
shows as a hidden head elsewhere.

`SyncStore` cannot be `internal`, as Kotlin has no friend modules and `core:sync` is
another module. No app needs it, so `@SyncStoreApi`, an opt-in at error level, makes any
other use a compile error.

## Consequences

### Positive

- A crash at any point repeats work at most. The simulator crashes at every step.
- A bad file is visible, in `SyncReport.issues`, and has no effect on anything else.
- A segment lost on a backend comes back while any device that read it, or its writer,
  still syncs there.
- A user's edit is visible, or listed with its text, whatever is missing.

### Negative

- A corrupt file stays a gap for good, one read per cycle.
- A segment nobody living read, lost after its writer died, is gone. Notes that build on
  it wait with hidden heads until the user settles them.
- A settled head's edit is merged by hand or not at all, and a missing ancestor that
  comes later does not undo that.
- A hidden head can wait a long time with a `WAITING` merge read on every cycle.
- User revisions are still rejected from whichever end of an edge arrives last. With a
  buggy device's bad stamp, two devices can keep different ones of the two revisions.
  A merge cannot do this, as it waits.
- ADR-0004's collision: removing the backend and adding it again now works, as numbers
  go above the folder. Two copies of a database still share a device id and race for
  numbers. Giving a copied database a new id is still open.
- **Known limit: wrong merge text.** Received merges are not derived again. The hash shows
  only that nothing changed since the merge was made, so a merge with wrong text, a valid
  hash and a lower id than the honest twin beats it (ADR-0013) and nothing notices. We
  accept that: a user's own devices run the same code, so a wrong merge is a bug in that
  code, which would derive the same text again.
- A segment read from a listing that is wrong gets pushed again: a duplicate, no harm.
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
