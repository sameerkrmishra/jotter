# core:sync

The sync engine ([docs/PLAN.md](../../docs/PLAN.md) §5 and §6): revision DAG
operations, automatic merges, change segments, and push, pull and apply over a
`BlobStore`.

```kotlin
val engine = SyncEngine(store, DeviceInfo("Laptop", "linux"), Dispatchers.IO)
when (val result = engine.sync(SyncTarget("usb-stick", blobStore))) {
    is SyncResult.Completed -> show(result.report)
    is SyncResult.Unreachable -> retryLater()
    // ...
}

val conflicts = ConflictResolver(store)
conflicts.observeOpen() // Flow<List<OpenConflict>>
conflicts.details(mergeId) // both texts, and the text with conflict markers
conflicts.resolve(mergeId, ConflictResolution.KeepBoth)
```

**Dependency rule:** depends on `core:model`, `core:merge`, `core:storage` and
`backend:api`, and on nothing that knows a backend's kind. Everything but the API
below is `internal`.

## API

| Type | What it is |
|---|---|
| `SyncEngine` | `sync(target)` runs one cycle: push, pull, apply. `forget(id)` drops a backend's local state. Scheduling is the caller's |
| `SyncTarget`, `DeviceInfo` | A backend with its lasting local id, and what `device.json` says |
| `SyncResult` | `Completed`, `Unreachable`, `Incompatible` or `Interrupted` |
| `SyncReport`, `SyncIssue`, `SegmentProblem` | What a cycle did, and what it could not do |
| `ConflictResolver` | `open`, `observeOpen`, `details`, `resolve`; for heads a note cannot merge yet `hidden`, `observeHidden`, `settleHidden`; for text that does not count `invalid`, `observeInvalid` |
| `ConflictDetails`, `ConflictResolution`, `NoSuchConflictException`, `NoSuchHiddenHeadException`, `HiddenHeadWaitsForConflictException` | For the conflict screen |

`SyncReport.merges` above zero means the backend has not seen them: they go out with
the next cycle.

## Decisions

The merge rules are in
[ADR-0013](../../docs/adr/0013-merge-base-and-covered-heads.md), failure handling in
[ADR-0014](../../docs/adr/0014-sync-failure-handling.md).

- **A note's state is a function of its set of revisions.** `foldHeads`,
  `openConflicts`, `liveHeads` and `shownHead` read nothing else: no arrival order, no
  device, no clock. That is the whole argument for convergence: devices with the same
  revisions make the same merges and show the same note.
- **Every merge is pure.** `mergeRevisions` takes a graph and two revisions. Database
  and network code stay in `Reconcile`, `Pusher` and `Puller`.
- **The merge base** is the highest-stamped best common ancestor that has text.
- **Covered heads.** A merge head whose user revisions another head also has does not
  count. Without this, devices out of step merge each other's merges for ever.
- **While a conflict is open, the note waits**, as ADR-0003 says, and so does a note
  that lacks an ancestor. Both are still shown, and can be edited.
- **An edit is never hidden in silence.** A live head a note does not show is listed by
  `ConflictResolver.hidden` with its text, until it merges or the user settles it. The
  UI must show this list, as a missing ancestor may never come.
- **What counts is judged from the revisions alone.** `RevisionGraph` decides, from a
  revision and its ancestors. Everything readable is stored and passed on, so one bad
  stamp cannot make devices hold different revisions. The UI must show
  `ConflictResolver.invalid`, or that text is out of sight.
- **Files are named by their content**, and pull tracks names, not numbers. A restored
  backup reads what it lost from its own folder, and nothing can overwrite anything.
- **`okio`** gives SHA-256 and gzip in common code. It adds no new dependency to the
  project, and `kotlinx-serialization-json` was already used by `interop:keep-import`.
- **`core:storage` exposes `SyncStore`** to this module: plain reads and writes, no
  rules. Kotlin has no friend modules, so it cannot be `internal`. No app needs it, and
  `@SyncStoreApi` makes any other use a compile error; this module's build opts in.

## Formats

These are permanent, and pinned on the JVM and `linuxX64`:

- the canonical bytes of a merge (`MergeHashTest`, specified in ADR-0013);
- the segment JSON (`SegmentCodecTest`, with a file compressed by Python's `gzip`, so
  the decoder is not only tested against its own encoder);
- whole simulated histories (`PinnedRunTest`): the same seed gives the same digest of
  every merge and note on both platforms.

## Tests

`ConvergenceTest` is the exit criterion of M3. `Simulation` runs real SQLite databases
over `InMemoryBlobStore`, with a wrapper that can lose a file. Everything comes from
one seed: ids, clocks, faults and choices, so a failure names a seed that replays.

| In a case | |
|---|---|
| Devices | 2 to 5, each on one backend; device 0 relays between all of them. Others can die for good, forget a backend and add it again, or go back to an old backup, also of a whole disk with a backend on it, with text of one length. A backup can also start a further device |
| Backends | 1 to 3 |
| Edits | Create, edit, pin, state, tags, delete forever, imports of one source on several devices |
| Delivery | Partial sync, files held back from a listing, so segments arrive late and out of order |
| Faults | `list`, `read` and `writeNew` failures, a write that fails after publishing, outages, lost segments, damaged files, sound files under a wrong hash, forged merges, bad stamps in a segment with good revisions |
| Crashes | After a write, after recording a segment, inside the apply transaction; plain restarts |
| Conflicts | Resolved at random, in all four ways |

Then every fault stops and the living sync until a round changes nothing. They must
then hold the same revisions, live heads, notes, open conflicts and hidden heads, and
with everything answered each note must have one live head, which it shows. No forged
merge or bad stamp may count, and no line of a body and no title may vanish unexplained.

`check` runs 3000 seeds on the JVM, about 3 minutes here, and 25 on `linuxX64`, whose debug
binary is far slower, plus `REGRESSION_SEEDS` on both. `nightly.yml` runs 50000 seeds, each night a new range, and opens an issue if one fails. By hand, set `JOTTER_SIM_CASES` and
`JOTTER_SIM_START`:

```
JOTTER_SIM_START=1000 JOTTER_SIM_CASES=5000 ./gradlew :core:sync:jvmTest --tests ConvergenceTest
```

Besides converging, every stored merge must follow from its parents and their
ancestors: the simulator computes each one again from the final DAG. `LineHistory` follows
each line through the DAG, with none of the rules of the code under test, and a line
missing from a live head must be explained by a person: an edit, a resolution, or a
deletion with no edit beside it. A line that only a conflict's losing side has must have
an open or answered conflict.


The tests of `SyncEngineTest` each pin one failure rule. Android host tests are
disabled, as in `core:storage`.
