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
| `isPinned`, `state` | Last writer wins by hybrid logical clock (HLC) timestamp, device id as tiebreaker |
| Delete vs. edit | The edit wins and the note comes back in the trash (`TRASHED`) |

**On conflict**, the head with the higher HLC timestamp (device id as
tiebreaker) stays current, so every device picks the same one. The other head
is stored in the `conflict` table. The UI offers "keep mine", "keep theirs",
"keep both as separate notes" or "edit merged text". Resolving creates a merge
revision with both heads as parents.

**Determinism.** A merge revision's id is a hash of its sorted parent ids and
its content. Its timestamp is the maximum of its parents' timestamps and its
author is recorded as "merge". Every field derives from the parents alone, so
two devices that auto-merge the same heads produce the same revision, which
then deduplicates by id.

**LWW fields in merge revisions.** For `isPinned` and `state`, a merge
revision does not stamp the winning value with its own timestamp or author.
It carries the winning value together with that value's original (HLC
timestamp, device id). Later LWW comparisons use those original stamps, so a
merge introduces no tie-break bias of its own. The merge revision's own
timestamp is still the maximum of its parents' timestamps. How these
per-field stamps are stored is settled in M3.

Assumptions where PLAN.md is not explicit:

- With more than two heads, a device does a left fold over the heads it
  currently has, sorted by revision id. Each step is a real merge revision,
  persisted and synced, whose parents are the two revisions it merged. The
  fold stops at the first step that conflicts; that pair becomes a conflict
  record.
- This is deterministic only for a fixed set of heads. It is not
  order-independent: a device that sees {A, B} and later C produces
  different intermediate merges from one that sees {A, C} and later B.
  Convergence comes from merging deterministically whatever heads are
  present, and repeating until one head remains. The differing intermediate
  merges become heads themselves and are merged in a further step.
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
