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
| `ConflictResolver` | `open`, `observeOpen`, `details`, `resolve` |
| `ConflictDetails`, `ConflictResolution`, `NoSuchConflictException` | For the conflict screen |

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
  and network code stay in `Reconcile`, `Push` and `Pull`.
- **The merge base** is the highest-stamped best common ancestor that has text.
- **Covered heads.** A merge head whose user revisions another head also has does not
  count. Without this, devices out of step merge each other's merges for ever.
- **While a conflict is open, the note waits**, as ADR-0003 says, and so does a note
  that lacks an ancestor. Both are still shown, and can be edited.
- **`okio`** gives SHA-256 and gzip in common code. It adds no new dependency to the
  project, and `kotlinx-serialization-json` was already used by `interop:keep-import`.
- **`core:storage` exposes `SyncStore`** to this module: plain reads and writes, no
  rules. Kotlin has no friend modules, so it is public and documented as off limits.

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
| Devices | 2 to 5, each on one backend; device 0 relays between all of them |
| Backends | 1 to 3 |
| Edits | Create, edit, pin, state, tags, delete forever, imports of one source on several devices |
| Delivery | Partial sync, files held back from a listing, so segments arrive late and out of order |
| Faults | `list`, `read` and `writeNew` failures, a write that fails after publishing, outages, lost segments, damaged files |
| Crashes | After a write, after recording a segment, inside the apply transaction; plain restarts |
| Conflicts | Resolved at random, in all four ways |

Then every fault stops and devices sync until a round changes nothing. All devices
must then hold the same revisions, live heads, notes and open conflicts, and with
every conflict answered each note must have one live head, which it shows.

`check` runs 200 seeds on the JVM and 25 on `linuxX64`, whose debug binary took 12 minutes
for 200. For more, set the environment variables `JOTTER_SIM_CASES` and
`JOTTER_SIM_START`:

```
JOTTER_SIM_START=1000 JOTTER_SIM_CASES=5000 ./gradlew :core:sync:jvmTest --tests ConvergenceTest
```

The tests of `SyncEngineTest` each pin one failure rule. Android host tests are
disabled, as in `core:storage`.
