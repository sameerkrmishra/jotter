# 0014. Failures in the sync cycle: validity, content names, repairs, restored backups and limits

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

**Local tables** (schema 2): `sync_read` for the files read in each folder of a backend,
`sync_segment` for this device's own segments, `backend_revision` for what is known to be
on a backend, `conflict` as PLAN.md §4 says, `hidden_head` and `invalid_revision`, and
the local `dismissed_invalid` and `combined_draft` (below). `backend_id` is opaque, as the `backend` table
comes with M4.

**Validity is a function of a revision and its ancestors.** Never of the order of
arrival, the local clock or the rest of its segment. So the cycle stores and passes on
every revision it can read, and `RevisionGraph` decides which count, from the set of
revisions alone:

- A user revision must be stamped above each of its parents known so far. If a parent
  has not arrived, that check waits, and the revision counts for now.
- No revision may have the highest timestamp there is: no clock can go above it, so an
  edit on it could not be made.
- A merge counts only with both parents here, and must have the stamp and loser they
  decide. A conflict is between two texts, so a tombstone cannot be its loser. Until then
  a merge is treated as not arrived, which is what makes it order-free.
- A revision on an invalid one is invalid, and so is one whose parent is stored under
  another note. A cycle is invalid: it needs a child stamped below its parent.
- A collided id does not count (below).

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

Local edits on a revision whose parent arrives late and proves it invalid are invalid
too, and are listed. This is the price of letting a user go on working: they stay
readable, and can be saved as a new note.

**Segment names (amends ADR-0004 and PLAN.md §6.2).** A segment file is
`NNNNNNNNNNNN-<hash>.json.gz`: twelve digits of number, then the content hash, which is
the first 16 bytes of the SHA-256 of the segment's JSON text before gzip, as 32 lowercase
hex digits. The JSON holds the device and the number, so the hash covers them, and it does
not depend on the compressor. Any other name is ignored. No backend has shipped, so
nothing needs migrating.

So a path has one possible content, and `BlobAlreadyExistsException` for a segment means
the same segment is already there. Numbers only order the files, for people and for
`MISSING`. They are not identity, and two files may share one.

**The read set.** `sync_read` holds, per backend, the names read in each folder, this
device's own included. What is unread is what the listing shows and the set does not: that
is the whole pull. A late file, a file numbered below others, and a second file with the
same number are all simply unread. A damaged file, a future format or a file that could
not be read stays unread and is tried on every cycle, so it costs a read per cycle, blocks
nothing, and applies by itself once it is repaired or an update can read it.

**Verifying on read.** The hash is checked after the format, as a newer format may hash
differently and must not read as damage. A mismatch fails as `CORRUPT` and says why.
Nothing of that file is stored and it stays unread, so it poisons nothing. A good copy
has the right name, which is another path, and is read as usual.

**What changes the set.** Only a transaction that applied the file. `list` errors, and any
`BackendUnavailableException`, end the cycle as `Interrupted`: what was already read is
applied, and nothing else moves. An empty listing from a reachable store is believed, as
`BlobStore` promises. Every file in the set then counts as lost, as below, and the device
pushes what it holds, which costs duplicates and no data.

**Publishing.** A segment is recorded in one transaction with its name, number and
revision ids, before `writeNew`. From then on it does not change. Any failure leaves it
recorded, and the next cycle writes the same file. A success, or
`BlobAlreadyExistsException` with a file there that has the name's hash, marks it published
and records it as read, as it holds nothing this device lacks. `BlobStore.writeNew` asks the
caller to compare, as a failed write can leave a bad file, and the store cannot help. If
the file does not have that hash, or no file is there, the segment is given up: its row
goes, its revisions count as not on the backend, and they go out in a new file, with a
higher number and so another name. The damaged file stays, as nothing may delete it, and
readers report it.

**Numbers.** A new segment is numbered above both what this device wrote on the backend
and the highest number in its folder when the cycle began. That keeps numbers rising after
a backend was forgotten and added again. If it fails, the harm is a shared number.

**Repair.** The cycle lists its own folder, and a published segment missing from it
is written again under its name, which fills the reader's hole. This answers the "hole"
ADR-0012 leaves to M3. A file that was read and is gone from the listing leaves the read
set, and its revisions count as not on the backend again, so the reader pushes them under
its own name. That covers a dead writer; with a live one it costs a duplicate. `MISSING`
is reported on every cycle, not once.

**Restored backups and copies of a database.** This settles ADR-0004's open point, and
amends its rule that one device is the only writer of a folder. The rule now is: **a path
has one content, and a folder can have several writers after a database was copied.**

- A device restored from a backup keeps its id. Files in its own folder that it does not
  remember are unread, so it reads them like any others, and its lost tail comes back.
- It may then use a number again. The content differs, so the name does, and both files
  stay and are read by everyone. If the content is the same, so is the file.
- Two copies of one database can run for good under one id, in one folder. They read each
  other's files there, and cannot overwrite them. Their revisions carry the same device
  id, which is harmless: the revision id breaks a tie in the stamp order.
- A disk restore that rolls back a database and a local-folder backend together is the
  same case. The files that left the backend are lost files, as above, and whoever read
  them pushes their revisions. What only that disk held is gone, like any lost history.

**Why device renewal went.** An earlier version of this ADR minted a new id whenever a
device saw a number it did not know, to keep one writer per folder. That hid the real
flaw: a number was trusted as identity. In simulator seed 263 a clone that had renewed took
a cursor for its old folder from its own last number, with no record of reading. The
device it was cloned from then lost a file there and wrote that number again with other
content, and the clone never read it: two of six revisions stayed missing, with nothing
reported. Comparing sizes cannot see that, as the sizes may match. Names can, and they
remove renewal with all it took: a device id that changes under a running process, cursors
carried over, numbers adopted, and an issue the UI would have had to explain.

**Two revisions under one id** can only come from a bug, as ids are random. A device that
sees both lists both as `ID_COLLISION`, and neither counts, nor does anything built on
them, whichever came first. Rows are never updated, so the first copy stays stored and is
the one passed on (see Negative).

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

**An answer names the head it was made on.** `settleHidden`, like `resolve`, takes the
head the user saw. If the note shows another one when the answer is written, nothing is
written and the caller learns the head now shown, so that a newer edit is never replaced
by an answer made without it.

**Local decisions stay local.** Text that does not count can be saved as a note, or
dismissed after the user confirms. Either way the device records it in
`dismissed_invalid` and stops listing it. That table is not synced: dismissing says what
one person has looked at on one device, the text itself stays stored and relayed, and a
synced dismissal would need a new kind of revision, so a format change, for no gain in
safety. Another device lists the text until it is dismissed there too. Texts the user
combines by hand to answer a conflict or a hidden head are kept in `combined_draft`,
also local, until the answer is written, which deletes the draft in the same
transaction, or the user drops it.

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
- A restored backup gets its lost tail back, with no action by the user, and the id of a
  device never changes.
- No file can overwrite another, and a rewritten number cannot go unseen.
- A damaged or tampered file is noticed before it is parsed as a segment.

### Negative

- Every note's history is judged on every reconcile, so cost grows with history.
- Invalid revisions stay stored and relayed for good.
- A corrupt file stays unread for good, one read per cycle.
- `sync_read` grows by a row per file on the backend, for each device.
- A permanent hole reports `MISSING` on every cycle: also a folder that starts high,
  after a backend was emptied and added again.
- A segment nobody living read, lost after its writer died, is gone. Notes that build on
  it wait with hidden heads until the user settles them.
- A settled head's edit is merged by hand or not at all, and a missing ancestor that
  comes later does not undo that.
- A user revision whose parent never arrives can never be judged, so it counts.
- **Known limit: bit rot after publishing (v1).** The writer lists names, not content, so
  it does not notice a file that decays later, and nothing repairs it. Every reader
  reports `SegmentNotApplied` with `CORRUPT` for that backend on every cycle, for good: it
  is never hidden, and it blocks no other segment. What a user can do: the writer still holds
  those revisions, and so does any device that read the file before it decayed. Removing
  the backend on such a device and adding it again (`SyncEngine.forget`, PLAN.md §6.5) marks
  all its revisions as not on the backend, so it pushes them in new files and the readers
  get them. A second backend that holds them does the same through a relay. This costs a
  full copy of that device's history, and the issue stays, as the file does. It needs no
  repair command, and M6's backup docs describe it. If the writer and every such device are
  gone, those revisions are lost.
- **Known limit: a relay carries one copy of a collided id.** A device that never sees the
  second copy keeps the first as a normal revision, while one that saw both counts neither.
  They disagree for as long as that lasts. It takes a repeated random id, which only a bug
  can cause, and both texts stay stored or listed on the device that saw both.
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
- **A cursor per folder, with a table of gaps:** the first version of this ADR. It stores
  little, but holds a number to mean one content, which a restored device breaks, and the
  listing has only sizes to tell them apart. A rewrite of the same size went unseen, in
  12 of 40 runs of a disk restore with same-length text.
- **A new device id after a restore:** see above. Content names make it unnecessary.
- **Stop a folder at the first bad segment:** the simplest cursor, but one damaged
  file blocks a device's later revisions for good.
- **The hash of the gzip file:** no need to inflate to check it, but the compressor would
  become part of the format, and the same segment could have two names.
- **Rewriting a lost segment's revisions under a new number:** works, but its old number
  stays a hole for good.
- **Re-deriving every received merge:** would catch wrong text, at the cost of a full
  merge per received merge, and needs a policy for what then happens to a merge
  other devices have built on.
