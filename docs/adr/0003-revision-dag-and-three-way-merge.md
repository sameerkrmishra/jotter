# 0003. Revision DAG with three-way merge and conflict records

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Devices edit notes offline and sync later
([ADR-0001](0001-local-first-architecture.md)). Two devices will edit the same
note concurrently, for example ticking item 3 of a checklist on the phone while
adding item 7 on the PC. We want such edits to merge automatically, and we must
never silently lose text. Any two devices that see the same edits must reach
the same result, or merges will ping-pong between them.

## Decision

Every edit creates a new **revision** whose parent is the revision it edited.
Revisions form a directed acyclic graph per note, like git commits, stored in
the `revision` table. A note with more than one head revision has concurrent
edits, and the device merges them field by field
([PLAN.md §5](../PLAN.md#5-merge-rules)):

| Field | Rule |
|---|---|
| `body`, `title` | Three-way line merge (diff3) against the common ancestor. Non-overlapping edits merge. Overlapping edits are a conflict |
| `tags` | OR-set: additions on either side are kept, removals on either side apply, and an add wins over a concurrent remove |
| `isPinned`, `state` | Last writer wins by stamp order (HLC timestamp, device id, revision id; see below) |
| Delete vs. edit | The edit wins and the note comes back in the trash (`TRASHED`) |

**Stamps and ordering.** Every revision carries a stamp: an HLC timestamp
and a device id. Wherever this ADR compares revisions ("higher stamp", "last
writer"), it uses one total order: HLC timestamp, then device id, then
revision id. Device ids and revision ids are compared as unsigned byte
strings, so every platform orders them identically. The revision id is
needed as the final tiebreaker because two automatic merges can inherit the
same stamp (see below).

**Two kinds of revision.**

- A *user revision* is anything a person does: an edit, a pin, a delete, or a
  conflict resolution. It gets the device's fresh HLC timestamp, which is
  strictly greater than its parents', the device's id, and an ordinary unique
  revision id (UUIDv7).
- An *automatic merge* is created by the merge code. Its id is a hash of its
  sorted parent ids and its content. Its stamp is the greatest of its
  parents' stamps. The device that computed it is not recorded. Every field
  derives from the parents alone, so two devices that auto-merge the same
  heads produce the same revision, which deduplicates by id.

**LWW fields in automatic merges.** For `isPinned` and `state`, an automatic
merge carries the winning value together with that value's original stamp,
not its own. Later LWW comparisons use those original stamps, so a merge
introduces no tie-break bias. How these per-field stamps are stored is
settled in M3.

**On conflict**, only `body` and `title` are in conflict. The device still
writes a deterministic automatic merge with both heads as parents. Its tags
and LWW fields are merged by the rules above. Its `body` and `title` come
from the head with the higher stamp, so every device picks the same one. It
also records that it is conflicted and which parent lost; this is part of its
hashed content. A device that applies such a revision, whether it computed
it or received it, adds the losing parent's text to its local `conflict`
table and shows a badge.

The conflicted merge replaces the two conflicting heads. While the conflict
is open, the note shows the conflicted merge's `body` and `title`, which is
the higher-stamped head's text. New edits take that conflicted merge (or a
later descendant) as parent, as usual, and the conflict stays open.

The UI offers "keep mine", "keep theirs", "keep both as separate notes" or
"edit merged text". Choosing one creates a user revision whose parent is the
head that descends from the conflicted merge. The resolution names the
conflicted merge it resolves, which is how other devices learn that the
conflict is closed. Two devices that resolve the same conflict differently
create two heads, which are merged like any other concurrent edits. The exact
encoding of the conflict marker and of the resolution's reference is settled
in M3.

**More than two heads.** A device does a left fold over the heads it
currently has, sorted by revision id (byte order). Each step is a real
automatic merge, persisted and synced, whose parents are the two revisions it
merged. If a step conflicts, it writes the conflicted merge described above
and the fold stops. Heads not yet consumed stay heads, and automatic merging
of that note waits until the conflict is resolved.

This is deterministic only for a fixed set of heads. It is not
order-independent: a device that sees {A, B} and later C produces different
intermediate merges from one that sees {A, C} and later B. Convergence comes
from merging deterministically whatever heads are present, and repeating
until one head remains. The differing intermediate merges become heads
themselves and are merged in a further step.

Further details:

- When several common ancestors are candidates (a criss-cross merge), the
  choice of ancestor is deferred to M3.
- The merge code lives in `core/merge` and is pure Kotlin with no I/O.

## Consequences

### Positive

- Common concurrent edits to Markdown lists merge without user action.
- Nothing is lost: unmergeable edits become visible conflicts, and deletes
  never beat edits.
- Revisions are plain records, easy to inspect in SQLite or in sync files.
- Merges are deterministic, which makes convergence testable with property
  tests over many simulated devices.

### Negative

- Line-based merge is coarse. Two edits to the same line conflict even when
  they touch different words.
- Full history is kept for every note and grows until compaction (deferred).
  Compaction must preserve common ancestors that are still needed.
- Finding the common ancestor and merging is more code to get right than
  last-writer-wins on the whole note.
- While a conflict is open, automatic merging of that note pauses. Concurrent
  edits from other devices stay as separate heads until the user resolves
  it.
- HLC-based LWW for pin and state depends on reasonably sane device clocks;
  HLC limits but does not remove the effect of a badly wrong clock.

## Alternatives considered

- **Text CRDTs (Automerge, Yjs):** merge character-level edits with no
  conflicts. Rejected because there is no mature Kotlin Multiplatform
  implementation, the per-character metadata makes storage heavier, and the
  stored data is hard for a human to read or debug.
- **Last writer wins on the whole note:** simple, but silently drops one side
  of every concurrent edit.
- **Always keep both copies on concurrent edits:** safe, but creates
  duplicates for edits that would have merged cleanly.
