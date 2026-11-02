# 0011. Touching changes merge, and the merge rules are part of the sync format

- **Status:** Accepted, amended by [0013](0013-merge-base-and-covered-heads.md)
- **Date:** 2026-11-02

## Context

[ADR-0003](0003-revision-dag-and-three-way-merge.md) says that non-overlapping
edits of `body` and `title` merge and overlapping edits conflict, but not whether
two changes that touch overlap. `core/merge` had to decide, and the answer
changes which notes need a person: ticking two neighbouring checklist items on
two devices is the core case of [PLAN.md §5](../PLAN.md#5-merge-rules). An open
conflict is expensive here, because it needs the user and automatic merging of
the note pauses until they act.

An automatic merge's id hashes its content, so the merged text is not only what
the user sees. Two versions of the merge code that disagree on a text produce two
different revisions from the same heads.

## Decision

This amends ADR-0003 for `body` and `title`.

- **Touching changes merge.** Changes to adjacent lines, and an insertion right
  next to a changed line, are not a conflict. Git and classic diff3 do conflict
  here, because they need an unchanged line between two changes. They merge
  source code, where neighbouring lines usually depend on each other. A note is
  often a list of independent lines. Notes also tend to have no final line
  break, so adding a line after the last one changes that last line, and the
  git rule would conflict on ticking the second-last item and adding one. Two
  touching changes never replace the same base line, so the base gives their
  order and nothing is lost.
- **What still conflicts.** Two changes that share a base line, an insertion
  strictly inside lines the other side changed, and different insertions at one
  position, including one that extends the other. Different insertions at one
  position have no order that does not favour a side, and the head order must not
  change the content.
- **A join must not alter a line.** A line without a break in front of another
  line, or a `\r` in front of a line that is only `\n`, would read as a different
  line once joined. The merged lines are split again, and if they differ, the
  whole merge is redone with touching changes as conflicts. Every join then comes
  from one of the inputs, so it cannot alter a line.
- **Identical insertions count as one.** At one position this is plain equality.
  An insertion that touches the other side's change counts too, if all its lines
  repeat that change's new lines at the end that touches it: base `b`, ours
  `B`, `milk`, theirs `b`, `milk` gives `B`, `milk`. Anything less stays an
  ordinary touching change, so an insertion that only partly repeats the change
  is kept whole, and such a line can appear twice. A repeat at the far end of the
  change says nothing about both sides adding the same thing, and a list may
  really hold a line twice.
- **Format.** The merge rules and `MAX_EDITS`, the diff's search limit, decide
  merged text, and merged text is hashed. They are part of the sync format, and
  changing either needs a format version. A pinned test covers a merge beyond
  the limit on the JVM and on `linuxX64`.
- **Fold order.** ADR-0003's left fold over several heads can give different
  clean texts in different orders, because a merge is not associative. Under
  random stress generators, 5.0%, 14.0% and 19.2% of 13 million three-head cases
  gave different clean texts (4.9%, 13.7% and 18.4% without the repeat rule).
  These are not rates for real notes. A later merge of those results reconciles
  them, and it usually conflicts, so the choice between the texts usually goes
  to a conflict the user sees. The original base is not a best common ancestor
  of two fold results: each head or intermediate merge they share can be one,
  depending on the order. ADR-0003 defers the ancestor in such a criss-cross to
  M3: choosing it deterministically when several best common ancestors exist is
  left to `core:sync`, and this choice affects hashed merge text. The fold is
  not a convergence risk, because each merge reduces the number of heads.

## Consequences

### Positive

- Ticks of neighbouring items, and a tick next to an added item, merge without
  the user.
- A line that one side only inserted and the other added at the touching end of
  its edit appears once. For a tick and two equal additions of a line the note
  does not already hold, that holds in either fold order.
- No merge rewrites a line: mixed line endings and a missing final break
  round-trip.

### Negative

- Two neighbouring lines that were each edited alone may no longer read well
  together, and nothing flags it. A person has to notice.
- A merge that would alter a line turns all touching changes in that note into
  conflicts, not only the one at fault.
- Partial repeats can still leave a line twice in a clean merge. So can two
  replacements: base `a`, `b`, `c`, ours `a`, `B`, `milk`, `c`, theirs `a`, `b`,
  `milk`, `C` gives `a`, `B`, `milk`, `milk`, `C`. Each side replaced a line, so
  there is no insertion to drop.
- If the note already holds an added line, the fold order can change how many
  copies stay: base `- [ ] a`, `milk`, `- [ ] c`, `milk`, one head ticking the
  first line and two heads adding `milk` after it give 3 or 4 `milk` lines.
- A fold can end in a conflict that another order would have avoided.
- Fixing a merge bug or tuning `MAX_EDITS` is a format change, not a refactor.

## Alternatives considered

- **Touching changes conflict, as in git and diff3:** simplest, and every join
  comes from one input. Rejected because neighbouring ticks would be conflicts,
  and each one pauses automatic merging of the note.
- **Keep both of two different insertions, in a fixed order:** no conflict for
  two items appended at one place. Rejected because it picks an order nobody
  chose, and blocks of lines could land in a confusing place without a warning.
- **Conflict when an insertion repeats a touching change:** avoids the duplicate
  line, but makes a user resolve two devices that agree.
- **Normalise line endings before merging:** fewer conflicts, but it silently
  alters lines and the text that is hashed.
- **Word-level merge:** merges two edits of one line, but needs its own rules for
  tokens and languages, and gives up "same line, same result" as a simple rule.
