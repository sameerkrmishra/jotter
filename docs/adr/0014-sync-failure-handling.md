# 0014. Failures in the sync cycle: validity, gaps, repairs, restored backups and limits

- **Status:** Proposed
- **Date:** 2026-11-02

## Context

[ADR-0004](0004-immutable-change-segments.md) defines the cycle and leaves open
what happens around a failure. Backends lie by omission: a segment can be late, can
be lost to a power cut after it was published
([ADR-0012](0012-local-folder-publishing.md)), or can be damaged or written by a
newer version. A failed `writeNew`, a failed retry included, may have published
the file. A device can have a wrong clock, a bug, or be restored from an old backup.
One bad file or revision must not poison a device, block the others, or make two
devices disagree for good.

## Decision

**Local tables** (schema 2): `sync_cursor` and `sync_gap` per backend and device,
`sync_segment` and `sync_last_number` for this device's own segments,
`backend_revision` for what is known to be on a backend, `sync_read` for the files read
from others, `conflict` as PLAN.md §4 says,
`hidden_head` and `invalid_revision`. `backend_id` is opaque, as the `backend` table
comes with M4.

**Validity is a function of a revision and its ancestors.** Never of the order of
arrival, the local clock or the rest of its segment. So the cycle stores and passes on
every revision it can read, and `RevisionGraph` decides which count, from the set of
revisions alone:

- A user revision must be stamped above each of its parents known so far. If a parent
  has not arrived, that check waits, and the revision counts for now.
- A merge counts only with both parents here, and must have the stamp and loser they
  decide. Until then it is treated as not arrived, which is what makes it order-free.
- A revision on an invalid one is invalid, and so is one whose parent is stored under
  another note. A cycle is invalid: it needs a child stamped below its parent.

Invalid revisions stay stored and are relayed, so that every device ends up with the
same set and the same verdicts. They and what builds on them do not count, and take
nothing else with them, as a segment is no unit of validity. Their text is never
dropped in silence: `ConflictResolver.invalid` lists each with its text, and a head
that loses its valid history falls back to the valid head before it, or removes the
note from the list if there is none.

A revision that decoding can make nothing of is left out alone, with the rest of its
segment applied, and its text listed as `MALFORMED`. All devices read the same file,
so they agree. A merge has no text of its own, so a merge with the wrong hash leaves
no row.

**Two revisions under one id** can only come from a bug, as ids are random. Each device
that sees both keeps the one whose JSON comes first and lists the other as
`ID_COLLISION`, whichever arrived first. The winner counts as on no backend, so it is
passed on. This is the only update of a revision row.

Local edits on a revision whose parent arrives late and proves it invalid are invalid
too, and are listed. This is the price of letting a user go on working: they stay
readable, and can be saved as a new note.

**A cursor and its gaps.** The cursor is the highest number processed in a folder, and
every range of numbers at or below it that was not applied is a row in `sync_gap`: a
range the listing skipped, a damaged or future-format file, or a file that could not be
read. Gaps are tried again on every cycle. So a bad file costs one read per cycle,
blocks nothing behind it, and applies by itself once it is repaired or an update can
read it. A range is one row, so no number is too high to accept, and a new device on a
folder that starts at 5001 reads it normally. There was a plausibility check here; it
locked such a device out.

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
path, marks it published. Other content there, or a file that cannot be decoded, ends
this device id (below).

**Numbers.** A new segment is numbered above both the highest number this device ever
used on the backend and the highest in its folder. So a backend that was forgotten
and added again, whose files stay (PLAN.md §6.5), causes no collision.
`sync_last_number` survives forgetting, as a number whose file was lost may be behind a
reader's cursor, and new content under it would never be read. A number that was
recorded but not known to be published does not count as used when it is forgotten,
as it can hold another writer's file.

**Repair.** The cycle lists its own folder, and a published segment missing from it
is written again with the same number and revisions, which fills the reader's gap.
This answers the "hole" ADR-0012 leaves to M3. A segment that was read from another
device and is gone from the backend, or now has another size, makes its revisions
count as not on the backend again, so the reader pushes them under its own name, and
its number becomes a gap, to be read again if it comes back. That covers a dead writer;
with a live one it costs a duplicate. `MISSING` is reported on every cycle, not once.

**Restored backups and copies.** This settles ADR-0004's open point. A device whose
folder on a backend holds a number above its `sync_last_number` has lost its past, or
another copy of its database is running, unless the segment holds only revisions it
has, as when one store is added under a new backend id: then it takes the number as
used. The same goes for another file at the path of a pending segment. Otherwise it
mints a new device id in one transaction, before it writes anything:

- It never writes to the old folder again, which keeps ADR-0004's single writer.
- The old id becomes an ordinary foreign device on every backend. Its cursor starts at
  the numbers this device remembers using, so the lost tail is read like any other
  segment and comes back. Numbers it never knew to be published are gaps.
- Its published segments count as read from that foreign device. Unpublished ones are
  given up, and their revisions go out again under the new id.

*If it sees the old folder only on some backends:* the check runs per backend, and
needs just one. Say the tail is on backend B. A sync with A first finds nothing wrong
and can write there under the old id, which harms nothing: the tail never reached A, so
no number there collides, and the folder on A has one writer until the id ends. The
first sync with B renews the id everywhere, as it is one per device, and from then on
all old folders are read as foreign. If it never reaches a backend with the tail,
it keeps the old id, and the tail waits on that backend.

*If two restored copies both do this:* each mints its own id, so they part without
coordination, and each reads the other's old tail. The cost is one abandoned folder per
id, revisions pushed again under the new ids, and one read of the tail per copy. If
neither sees a tail, both go on as one id until their writes meet: one finds the other's
file at its number, and renews. Copies on separate backends do not meet there, and each
folder still has one writer.

**Hidden heads.** A merge needs what lies between its two heads and their common
ancestor: a missing revision below that ancestor is hidden by it, and one above it could
be the best ancestor itself. `RevisionGraph.canMerge` asks exactly that. So a note merges
as far as it can, and only heads that truly cannot wait. Merging on what is known would
be safe only if nobody ever held the rest, which no device can tell, so that stays
forbidden. The rule instead: **an edit is never hidden in silence.** Every live head
with text that the note does not show is a row in `hidden_head`, with its text and the
reason, `MISSING_ANCESTOR` or `OPEN_CONFLICT`. The row is rebuilt from the revisions, so
it ends only when the head has merged, or when the user has dealt with it.
`ConflictResolver.settleHidden` writes a user revision on the shown head, whose `resolves`
names the hidden head, which then stops counting on every device. If the ancestor still
comes, nothing changes: the user has decided. A head that waits for an open conflict
cannot be settled, as that would pass over the conflict and its losing text.
`resolves` may therefore name any revision.

**Limits.** A file, compressed or inflated, is at most 32 MiB. Inflating stops at
the limit, so a small file cannot fill memory. A push splits revisions so that each
segment fits for certain, counting six bytes of JSON per UTF-16 unit, which is the
worst case. A pull applies once it holds about 32 MiB, in one transaction per batch.

**The size of a note.** `MAX_NOTE_CHARS`, 5,000,000 UTF-16 units of title and body, sits
below the roughly 5.5 million that fit a segment. `create` and `edit` fail with
`NoteTooLargeException`, and a resolution likewise, so editing cannot make a note that
can never sync. `jotter add` and the editor say so, and the editor keeps the draft. An
import over it writes nothing and answers `TOO_LARGE`, and the Keep import reports
`NOTE_TOO_LARGE` and goes on. A merge past it is a conflict (ADR-0013). A note already
over it stays readable, its tags, pin and state still change, and its text can only be
saved under the cap. Its revisions stay on the device with `RevisionTooLarge`, the push
goes on with everything else, and what later builds on them shows as a hidden head
elsewhere.

`SyncStore` cannot be `internal`, as Kotlin has no friend modules and `core:sync` is
another module. No app needs it, so `@SyncStoreApi`, an opt-in at error level, makes any
other use a compile error.

## Consequences

### Positive

- A crash at any point repeats work at most. The simulator crashes at every step.
- Devices with the same revisions agree on what counts, in every order of arrival.
- A bad file or revision is visible, and has no effect on anything else.
- A segment lost on a backend comes back while any device that read it, or its writer,
  still syncs there.
- A user's edit is visible, or listed with its text, whatever is missing or refused.
- A restored backup gets its lost tail back, with no action by the user.

### Negative

- Every note's history is judged on every reconcile, so cost grows with history.
- Invalid revisions stay stored and relayed for good.
- A corrupt file stays a gap for good, one read per cycle.
- A permanent hole reports `MISSING` on every cycle: also a folder that starts high,
  after a backend was emptied and added again.
- A segment nobody living read, lost after its writer died, is gone. Notes that build on
  it wait with hidden heads until the user settles them.
- A settled head's edit is merged by hand or not at all, and a missing ancestor that
  comes later does not undo that.
- A user revision whose parent never arrives can never be judged, so it counts.
- **Known limit: a rewrite of the same size.** If a file is lost and its number is then
  written with other content, by another copy of the same device id or after forgetting
  a pending number, nobody can tell from the writer's side. A reader sees it as a missing file or another size, and reads it again.
  A rewrite of exactly the same size that it never saw missing stays unread, as a
  listing has no more than sizes. That needs a lost file, a restored backup and an equal
  size at once, and the revisions stay on the device that wrote them. The simulator keeps
  those faults apart for this reason.
- **Known limit: wrong merge text.** Received merges are not derived again. The hash shows
  only that nothing changed since the merge was made, so a merge with wrong text, a valid
  hash and a lower id than the honest twin beats it (ADR-0013) and nothing notices. We
  accept that: a user's own devices run the same code, so a wrong merge is a bug in that
  code, which would derive the same text again.
- A segment read from a listing that is wrong gets pushed again: a duplicate, no harm.
- Every cycle takes a write transaction, even with nothing to apply, to settle notes
  with several heads.

## Alternatives considered

- **Reject bad revisions on arrival:** the first version. Whichever end of an edge came
  last was refused, with its whole segment, so one bad stamp left devices with different
  revisions for good, unrelated notes included. Judging from the set cannot do that.
- **Stop a folder at the first bad segment:** the simplest cursor, but one damaged
  file blocks a device's later revisions for good.
- **A set of applied numbers instead of a cursor:** no gaps table, but it grows with
  the log, where gaps are rare.
- **A limit on how far a number can jump:** cheap rows, but a legitimate folder that
  starts high then locks a new device out.
- **Rewriting a lost segment's revisions under a new number:** works, but readers
  keep a gap for the old one for good.
- **Re-deriving every received merge:** would catch wrong text, at the cost of a full
  merge per received merge, and needs a policy for what then happens to a merge
  other devices have built on.
- **Going on under the old id after a restore, above the tail:** recovers nothing by
  itself, and two copies would stay two writers of one folder.
