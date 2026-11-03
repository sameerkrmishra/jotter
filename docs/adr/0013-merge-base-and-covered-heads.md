# 0013. The merge base, covered heads and the canonical form of a merge

- **Status:** Proposed
- **Date:** 2026-11-02

## Context

An automatic merge's id hashes its content
([ADR-0003](0003-revision-dag-and-three-way-merge.md)), so whatever decides merged
text is permanent ([ADR-0011](0011-touching-changes-and-merge-format.md)). Three
things were left to M3: which ancestor to use when there are several best common
ones, the exact bytes that are hashed, and how a resolution names its conflict.

Building the sync engine showed a fourth. ADR-0003 says devices converge by merging
whatever heads are present until one remains. They do not, if they sync out of step.
Each device merges its head with the others' slightly older merges, each of those
merges is a new head for the others, and no round ever finishes: in the simulator
devices never settled, although nobody was editing. Each merge counted as news.

## Decision

This amends ADR-0003, and ADR-0011, whose "each merge reduces the number of heads" does
not hold: a merge of two heads out of step is one more head for the others. What ends
the merging is the rule on covered heads below. All of it is part of the sync format.

**The merge base.** To merge `a` and `b`:

1. The *text ancestors* of a revision are itself and what its text came from: the
   walk down its parents stops at a tombstone, which is not one of them. A merge of a
   deletion with an edit holds the edit's text alone, so what lies behind the deletion
   says nothing about it, and using it as a base would read that text as removed.
2. `C` is every revision that is a text ancestor of both.
3. The best common ancestors are the members of `C` that are not a proper text
   ancestor of another member of `C`.
4. The base is the one of these with the highest `Stamp`.
5. If `C` is empty, ADR-0009's rule applies to `body` and `title`, over the roots among
   the text ancestors of `a` and `b`, and tags have an empty base.

The base is used for `body`, `title` and tags alike, and a tag counts as added only by a
user revision among the text ancestors of one side alone. All of this reads only the
ancestors of `a` and `b`, so devices that know different things agree. The order of
`a` and `b` does not matter. Whether `a` and `b` can be merged at all is in
[ADR-0014](0014-sync-failure-handling.md).

Only `U` below walks through tombstones: it is about who wrote what, not about text,
and a merge must have all the user revisions of its parents for the merging to end.

**Covered heads.** Let `U(r)` be the user revisions among the ancestors of `r`. A
head counts as *live* unless it is an automatic merge that does not descend from an
open conflict, and another head `g` has `U(r)` as a proper subset of `U(g)`, or has
`U(r) = U(g)` and goes first: the one with text before a tombstone, as an edit beats a
deletion, and otherwise the lower id. Only live heads are folded, shown or merged. A head the
fold has come to cover on the way is passed over. A covered head stays in the DAG
and no revision is written about it.

A merge adds no edit of its own, so a covered head has nothing the other head lacks.
Every merge now has strictly more user revisions than either parent, and with no new
edits `U` is bounded, so the merges come to an end. The result depends on the set of
revisions alone. A head beside an open conflict is not covered, as resolutions build
on it. "Until one remains" now means one live head.

**What a note shows.** Of the live heads that are not tombstones, those descending
from an open conflict if there are any, the one with the highest stamp. A tombstone
only if no live head has text.

**Conflicts.** `Revision.resolves` holds the conflicted merge a user revision closes.
A conflict is open unless a stored revision names it, or a tombstone descends from it
and no head with text does, other than through a tombstone. With an edit beside the
deletion the conflict stays open, as the text that lost would otherwise be shown
nowhere. A merge of the tombstone with an unrelated edit descends from the conflict
only through the tombstone, holds nothing of it, and leaves it closed. Any open
conflict pauses the fold of the whole note, also for heads that do not descend from it,
as ADR-0003 says. A head named by `resolves` is not live
([ADR-0014](0014-sync-failure-handling.md)).

**Field rules made exact.**

- A tag on only one side stays if it is not in the base, or if a user revision among
  that side's ancestors, but not the other's, added it (the root's tags count as
  added). Comparing with the base alone could not see a tag removed and added back,
  and PLAN.md §5 says that an add wins.
- A snapshot merged with a tombstone keeps its fields, with state `TRASHED` stamped
  by the tombstone: a merge has no stamp of its own to give, and the tombstone is
  what put the note in the trash. A later write of the state beats it as usual.
- Two tombstones merge into a tombstone.
- A clean text merge that SQLite could not store unchanged is a conflict. Removing a
  first line can move U+FEFF to the start of the text, and hashed text must not be
  altered afterwards. So is one whose title and body together pass `MAX_NOTE_CHARS`:
  each side is under the cap, and a merge past it might not sync (ADR-0014).

**Canonical bytes.** The id is the SHA-256 of these bytes, big-endian. A *text* is a
32-bit length in UTF-8 bytes, then those bytes. A *stamp* is the 64-bit physical
time, the 32-bit counter, the device id as text and the revision id as text. Ids are
in their canonical text forms.

| Field | Form |
|---|---|
| Domain | The 16 bytes `jotter-merge-v1\n` |
| Note id | Text |
| Parents | 32-bit count, then each id as text, sorted ascending |
| Timestamp | 64-bit physical time, 32-bit counter |
| Device id | Text |
| Losing parent | Byte 0, or byte 1 and the id as text |
| Content | Byte 0 for a tombstone, or byte 1 and the rest |
| Title, body | Text each |
| Tags | 32-bit count, then each as text, sorted by UTF-16 code units |
| Pinned | Byte 0 or 1, then its stamp |
| State | Its name as text, then its stamp |

`MergeHashTest` pins these, and Python's `hashlib` gave the same id for the pinned
bytes. `PinnedRunTest` pins the digests of whole simulated histories. Both run on the
JVM and `linuxX64`.

## Why one ancestor and not a recursive merge

Git's recursive strategy merges the best common ancestors into a virtual base. We
measured what that would buy, with throwaway tests.

On generated criss-crosses, which is the worst case for this rule (200,000 cases
each of 10 to 100 lines, built so that every intermediate merge is clean, then edited
on both sides), the single base conflicts far more often:

| Lines, changes per step | Conflicts, single base | Conflicts, virtual base |
|---|---|---|
| 10, 1 | 62.7% | 22.4% |
| 10, 2 | 93.9% | 61.3% |
| 30, 2 | 72.4% | 27.1% |
| 30, 4 | 98.7% | 71.1% |
| 100, 3 | 58.6% | 18.7% |

Which of the two ancestors was used made no difference (at most 0.3 points apart). In
the cases where both are clean, their text differs in 4% to 54%.

Criss-crosses are rare in histories, though. Over seeds 1000 to 3999 of the simulator,
which syncs partially and out of step far more than real use, 142 of 5,918 merges of
two snapshots had several best ancestors, 2.4%. Of those, 65 conflicted with the
single base and 54 with a virtual base, so a virtual base would have avoided a
conflict in 11 merges, 0.19% of all. In 67 of the 142 the virtual base itself did not
merge cleanly, and a rule would be needed for that too.

We chose the single ancestor for these reasons, and a reviewer may weigh them
differently:

- It is permanent, and what it costs is small: a rare extra conflict, which loses
  nothing and which the user can resolve.
- A recursive merge fixes more format forever: the order of folding the ancestors,
  the base of each virtual step, what a virtual merge that conflicts stands for, and
  a depth limit, since recursion on a crafted DAG is exponential.
- A virtual base has no revision, so the conflict screen cannot show where it came
  from, and a bug in it is not visible in the data.

The highest stamp is as good as any choice by these numbers. It is the one most likely
to already hold both sides' recent work, and it needs no text comparison.

## Consequences

### Positive

- Devices settle, also out of step.
- Devices with the same revisions show the same note, whatever the arrival order.
- A second merge of the same edits costs a stored revision and no user attention.

### Negative

- A criss-cross conflicts more often than under a recursive merge, by the numbers
  above. A revert on one side of a criss-cross can also be undone silently if the
  chosen ancestor already has it, as with any single base.
- Of two clean merges of the same edits, one silently stays (ADR-0011 notes that
  their text can differ, for example in how many copies of a repeated line remain).
  Before this ADR a further merge would usually have made that a conflict. Nothing a
  user wrote is lost that the other merge did not also take.
- Covered heads stay in the DAG, so `note` rows of such notes are rewritten on each
  sync, and history shows merges that lead nowhere.
- `U` makes the fold cost grow with history. Notes with very many heads are slow; a
  crafted segment can cause that, though not unbounded memory.
- Fixing any of this is a format change.

## Alternatives considered

- **Recursive merge:** see above.
- **Lowest stamp, or the root:** an older base makes more conflicts, and the root
  ignores merges that were already agreed.
- **Stop merging merges: fold only the user frontier, in a canonical chain:** also
  terminates, and one set of user revisions always gives one chain. But it drops
  ADR-0003's fold, and every new edit would rewrite the chain.
- **Delaying merges until a device has nothing to pull:** only makes the storm less
  likely, and "nothing to pull" differs between devices.
- **Treating merges with equal content as one:** two merges of equal text still
  differ in id and parents, so their heads would stay, and merges with different text
  would not be helped.
