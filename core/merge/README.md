# core:merge

The three-way line merge (diff3) of `body` and `title`
([docs/PLAN.md](../../docs/PLAN.md) §5,
[ADR-0003](../../docs/adr/0003-revision-dag-and-three-way-merge.md)):
`merge(base, ours, theirs)` gives a clean text or a conflict. Heads, tags,
last-writer-wins fields, hash ids and choosing the base are `core/sync`'s job.

**Dependency rule:** pure Kotlin, no I/O, no platform APIs and no other Jotter
module. It works on plain strings, so it does not need `core:model`.

## API

| Declaration | What it is |
|---|---|
| `merge(base, ours, theirs)` | The merge. With no common ancestor, pass the empty base of [ADR-0009](../../docs/adr/0009-import-preserves-source-timestamps.md) |
| `MergeResult.Clean` | The merged `text` |
| `MergeResult.Conflict` | `chunks`, in text order: `MergeChunk.Resolved` for what merged, `MergeChunk.Conflicting` for the `ours` and `theirs` lines of a disagreement |
| `Conflict.withConflictMarkers(oursLabel, theirsLabel)` | Git-style markers for "edit merged text" (PLAN §5) |

A conflicted automatic merge stores the higher-stamped head's text, not the
chunks or the markers (ADR-0003), so those two never reach a hash. They exist
for the UI.

## Decisions

The merged text of a clean merge goes into the automatic merge's hash id, so
**every rule below, and `MAX_EDITS`, is part of the sync format**, and changing
one needs a format version
([ADR-0011](../../docs/adr/0011-touching-changes-and-merge-format.md)). Two
versions of the merge that disagree on a text produce different revisions from
the same heads. `theMergedTextOfAHardMergeIsPinned` fails if the behaviour of a
hard case moves.

- **Determinism.** The result depends only on the three strings. There is no
  locale, no platform line handling and no Unicode table: lines are cut only
  after `\n`, `\r\n` or `\r`, and they compare exactly by UTF-16 code units. A
  hash map numbers the lines, but only equality of the numbers is ever used, so
  its order cannot matter. The JVM and `linuxX64` run the same tests, including
  the pinned one.
- **The sides are symmetric.** `merge(b, x, y)` and `merge(b, y, x)` give the
  same `Clean`, and `Conflict`s that differ only by `ours` and `theirs` swapped
  in each chunk. The diff of a side depends on the base and that side alone, and
  a merge orders changes by their base position only. The one place where a
  merge would have to pick a side to go first, two different insertions at one
  position, is a conflict rather than a tie-break, so head order cannot change
  an automatic merge's content, and so its id.
- **Identical insertions count as one.** That holds at one position, and for an
  insertion touching the other side's change if all its lines repeat that
  change's new lines at the end that touches it: base `b`, ours `B`, `milk`,
  theirs `b`, `milk` gives `B`, `milk`. An insertion that only partly repeats the
  change, or matches its far end, is kept whole, so such a line can appear twice.
- **Touching changes merge.** Changes to adjacent lines, and an insertion right
  next to a changed line, are not a conflict. Git and classic diff3 do conflict
  here, because they need an unchanged line between two changes. We don't,
  because:
  - A note is often a checklist, and ticking two neighbouring items on two
    devices is the core use case of PLAN §5. Each tick has its own line.
  - An open conflict is expensive here. It needs the user, and automatic merging
    of the note pauses until they act (ADR-0003).
  - Notes usually have no final line break, so adding a line after the last one
    changes that last line ("c" becomes "c\n", "d"). Under the git rule, ticking
    the second-last item and adding one would conflict.
  - Two changes that touch never replace the same base line, so their order is
    given by the base and nothing is lost. The risk is two neighbouring lines
    that no longer read well together, which a person can see and fix; the note
    still holds everything both sides wrote.
- **What conflicts.** Two changes conflict if they share a base line, if one
  inserts strictly inside lines the other changed, or if both insert different
  lines at one position. That includes one insertion extending the other: only
  a whole region compares equal. All such changes form one region, and if its
  text is the same on both sides it is clean. Conflict regions are not trimmed
  of lines the sides share.
- **Lines are never altered.** A line keeps its break, and `"a\n"`, `"a\r\n"`
  and `"a"` are three different lines. So converting line endings on one side
  changes each line, and conflicts with an edit of any of them, but a merge
  never normalises. Text is cut only at `\n` and `\r`, which cannot be half of a
  surrogate pair, and unpaired surrogates pass through unchanged. U+2028,
  U+0085 and form feed do not end a line.
- **Joining must not alter a line.** Touching changes can put a line without a
  break in front of another line (`"b"` then `"c\n"` reads as `"bc\n"`), or a
  `\r` in front of a line that is only `\n`, which reads as one `\r\n`. Merged
  lines are therefore split again, and if they differ the whole merge is redone
  with touching changes as conflicts, which cannot happen under that rule:
  every join of lines then comes from one of the inputs.
- **Empty texts.** An empty text has no lines. With an empty base both sides are
  insertions at one position, so they merge if equal and otherwise conflict on
  all their lines, as ADR-0009 accepts. A side emptied against an untouched side
  merges to empty. A side emptied against an edit conflicts. Deleting a whole
  note's text next to the other side's appended line merges, and keeps the
  appended line.
- **Large bodies.** The diff is Myers' greedy algorithm, with a limit of 1024
  edits per search. Beyond that the search commits to its furthest point, the
  one nearest its own diagonal on a tie, and starts again from there. Each search
  gets at least 1024 lines further, so the work is O((N + M) × 1024) and the
  memory O(1024²), even for texts with nothing in common. Plain Myers would take
  about 10¹⁰ steps there. Two bodies of up to 512 lines each are always diffed
  exactly. Above the limit a diff is still correct, but it can be longer than
  the shortest, which can cost an extra conflict or place a change differently.
- **Line ids.** Lines are numbered through a hash map, which has no protection
  against crafted collisions. 65,536 colliding lines took 134 ms on the JVM and
  6 min 18 s on the Native debug binary. Only Native is quadratic here, and
  nothing ships there yet (ADR-0006).

## Performance

Median of 5 warm runs of `merge` on the JVM, on an Intel Xeon W-2135 with other
builds running, measured 2026-11-02 with a throwaway test. The `linuxX64` column
is the debug test binary, which is not optimised.

| Input | JVM | `linuxX64` debug |
|---|---|---|
| A note of 100 lines, one edit per side | 0.08 ms | 0.29 ms |
| 100,000 lines, one edit per side | 42 ms | 330 ms |
| 100,000 lines, a tenth of them edited on each side | 300 ms | 3 s |
| 100,000 lines of two repeated lines in random order | 860 ms | 7 s |
| 100,000 lines, each side rewrote all of them | 2.2 s | 24 s |

Callers must not run a merge of an unbounded body on the UI thread.

## Tests

Unit tests, plus Kotest property tests over arbitrary strings: every kind of
line break, repeated lines, non-BMP characters and unpaired surrogates. The
properties are `merge(a, a, b) == b`, `merge(a, b, a) == b`,
`merge(a, b, b) == b`, `merge(a, a, a) == a`, symmetry on related and unrelated
texts, that a clean merge holds only lines of its inputs, and that generated
non-overlapping edits always merge to exactly both edits. A generator of
touching edits, built from lines that are unsafe to join and from repeated
insertions, checks the laws, symmetry and that no line is altered. A second one
checks that lines added on both sides next to an edit appear once.

The tests for large bodies do not look at a clock. They count the diff's steps
(`DiffCost`) and require them to stay within the linear bound, on inputs built
to make a plain diff quadratic.
