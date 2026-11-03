# Jotter — Project Plan (v1)

A local-first, provider-independent notes app. Every device keeps its own SQLite
database and works fully offline. Devices converge by exchanging immutable
change files through one or more user-chosen storage backends (a local folder,
WebDAV, or the user's own PC over Tailscale).

---

## 1. Requirements summary

### v1 — in scope

| Area | Requirement |
|---|---|
| Platforms | Android app (F-Droid), native Linux desktop app, Linux CLI |
| Offline | Android and Linux work fully offline and sync when a backend is reachable |
| Convergence | When the PC or a backend comes back online, all devices converge to the same state |
| Storage | One SQLite database per device, in a single file. Copying it is safe when the app and CLI are not running (a clean close checkpoints the WAL and removes the `-wal`/`-shm` files). While the app runs, "Export database" writes a consistent single-file copy (`VACUUM INTO`) |
| Export | Export all notes, or all notes with a given tag, as Markdown files |
| Backends | Local folder and WebDAV (mailbox.org, NAS, Nextcloud). The user can add and remove backends at runtime |
| Conflicts | Merge automatically where possible. Otherwise keep both versions and offer manual merge |
| Notes | Markdown text only (checklists are Markdown task lists `- [ ]`). No attachments, no drawings |
| Organisation | Pin, tags, archive, trash (no auto-purge; "delete forever" is explicit) |
| Search | Full-text search |
| Capture | Android "Share to" target. Linux CLI (`jotter add "buy milk"`) |
| Import | Google Keep Takeout import (~1,000 notes) |
| Telemetry | None. Local debug logs only, exportable by the user |
| Users | Single user, no login |

### Explicitly deferred (documented, designed for, not built)

- End-to-end encryption of data on backends (§6.6 keeps the seam for it).
- Multiple users and login (family use).
- Dropbox, Google Drive and S3 backends.
- Neovim and Emacs plugins (they will drive the CLI or a local API).
- Snapshot and compaction of the change log.
- Web app served from the PC over Tailscale.

### Never

- Reminders, attachments, handwriting, sharing with third parties, analytics.

---

## 2. Storage layer options

Notes are small (10–100 lines). Thousands of notes means a few MB. Any option is
fast enough, so we choose for portability, correctness and readability.

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **SQLite via SQLDelight** | One portable file (meets the flash-drive requirement: copy it when the app and CLI are not running, or use "Export database"). FTS5 full-text search. SQL is visible and reviewable in `.sq` files. Type-safe generated Kotlin. Mature on Android and JVM | Generated code (excluded from coverage) | **Recommended** |
| SQLite via Room KMP | Google-backed, familiar to Android devs, now multiplatform | Annotation-heavy. SQL hidden in DAOs. Desktop support is newer | Viable alternative |
| Folder of Markdown files + index | Human-readable without the app | Needs a separate index for search. Metadata in front-matter. Multi-file atomicity is hard. A single SQLite file, copied when the app and CLI are not running or exported while they run, already meets the portability goal | Rejected for the live store. Kept as the **export** format |
| Realm Kotlin | Built-in sync | Deprecated by MongoDB, and its sync service is a vendor dependency | Rejected |
| ObjectBox | Fast | Opaque binary format, weaker portability | Rejected |
| Event log only + in-memory model | Simple model | Search and queries need rebuilding on every start. No single queryable file | Rejected (but the sync layer *is* an event log, §6) |

**Decision (ADR-0002):** SQLite with SQLDelight. Android's system SQLite does
not reliably ship FTS5 on any version, so we bundle our own SQLite build: the
androidx bundled SQLite driver on both Android and the JVM, behind a small
SQLDelight driver adapter, so FTS5 behaves identically everywhere. The M0 spike
([docs/spikes/0001](spikes/0001-fts5-sqldelight.md)) proved this on the JVM and
on Android emulators at API 26 and 36.

---

## 3. Architecture

### 3.1 Principles

1. **Local-first.** The local database is the source of truth for the UI.
   Sync is a background process that never blocks the user.
2. **Dumb backends.** Backends only *list, read and write files*. All merge
   logic runs on the devices. Adding a backend means implementing four
   methods.
3. **Append-only, never overwrite.** A device only writes files it owns, and
   never modifies them. This is safe on WebDAV, a USB stick, Dropbox-style
   folder sync, or anything else that lacks locking.
4. **Deterministic merges.** Any two devices that merge the same inputs
   produce the same result, so merges converge instead of ping-ponging.
5. **Dependencies point inwards.** UI → application services → domain. The
   domain module has no I/O, so it is easy to test to near 100%.

### 3.2 Module layout (Gradle)

```
jotter/
├── core/
│   ├── model/          # Note, Tag, ids, hybrid logical clock (pure Kotlin)
│   ├── merge/          # three-way Markdown merge (diff3) (pure Kotlin)
│   ├── sqlite/         # SQLDelight driver over the bundled SQLite (WAL, locking)
│   ├── storage/        # SQLDelight schema, repositories, FTS search
│   ├── sync/           # revision DAG, change segments, SyncEngine
│   └── logging/        # thin wrapper over Kermit + rotating file sink
├── backend/
│   ├── api/            # BlobStore interface
│   │   └── contract/   # contract test suite, for the tests of other modules
│   ├── localfolder/    # BlobStore over a filesystem directory
│   ├── webdav/         # BlobStore over WebDAV (Ktor client)
│   └── peer/           # BlobStore client for the PC hub server
├── interop/
│   ├── keep-import/    # Google Takeout → notes
│   └── markdown-export/
├── app/
│   ├── shared-ui/      # Compose Multiplatform screens and view models
│   ├── android/        # Activity, Share target, WorkManager sync
│   ├── desktop/        # Compose Desktop entry point, Linux packaging
│   └── cli/            # Clikt-based CLI
└── server/             # Ktor: peer BlobStore endpoint (web app API later)
```

Each module has one job and a small public API. Internal details are marked
`internal`.

### 3.3 Layers

```
 ┌──────────────────── app (android / desktop / cli) ─────────────────┐
 │  Compose UI, view models, platform glue (share intent, WorkManager)│
 └───────────────────────────────┬────────────────────────────────────┘
                                 │
 ┌──────────── application services (core/storage, core/sync) ───────┐
 │  NoteService (create/edit/tag/pin/archive/trash/search)           │
 │  SyncEngine (push/pull/merge per backend)  BackendRegistry        │
 └───────────────┬───────────────────────────────────┬───────────────┘
                 │                                   │
     ┌───────────▼───────────┐            ┌──────────▼──────────┐
     │ domain (core/model,   │            │ BlobStore interface │
     │ core/merge) — pure    │            │ local / webdav /    │
     └───────────────────────┘            │ peer (PC)           │
                                          └─────────────────────┘
```

### 3.4 Key technology choices

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin Multiplatform (Android + JVM; shared modules also compile and test on `linuxX64`; Wasm for web later) | One language and shared core for Android and Linux. `linuxX64` keeps a native CLI possible (ADR-0006) |
| License | Apache-2.0 | Free use; attribution required via LICENSE + NOTICE; patent grant |
| UI | Compose Multiplatform | One UI codebase for Android and Linux |
| DB | SQLDelight + bundled SQLite (FTS5) | §2 |
| HTTP | Ktor client; Ktor's sockets and HTTP parser for the hub ([ADR-0015](adr/0015-pc-hub-peer-protocol.md)) | KMP-native, used for WebDAV, peer and server |
| Serialization | kotlinx.serialization (JSON) | Readable change files, easy to debug |
| Markdown | JetBrains `markdown` parser + a Compose renderer | KMP, maintained |
| DI | Manual constructor injection with one composition root per app | Easiest to read. No framework to learn |
| Logging | Kermit + local rotating log file + "Export debug logs" | No telemetry, but debuggable |
| CLI | Clikt | Idiomatic, well-documented |
| Testing | kotlin.test, Turbine (flows), Kotest property tests (sync convergence), Robolectric (Android) | |
| Coverage | Kover | Enforced in CI |
| Lint / format | ktlint (`ktlint_official` code style, max line length 100) + detekt (strict) + Android Lint | Consistent, reviewable code |

---

## 4. Data model

```kotlin
data class Note(
    val id: NoteId,             // new: UUIDv7, generated offline; imported: deterministic
    val revisionId: RevisionId, // the revision it was materialised from
    val title: String,          // may be empty (Keep style)
    val body: String,           // Markdown
    val tags: Set<TagName>,
    val isPinned: Boolean,
    val state: NoteState,       // ACTIVE, ARCHIVED, TRASHED
    val createdAt: Instant,
    val updatedAt: HybridTimestamp,
)
```

Local SQLite tables (simplified; the schema is in `core/storage`):

- `note`: current materialised state of each note (what the UI reads).
- `note_fts`: FTS5 virtual table over title and body.
- `note_tag`: each note's current tags. There is no separate `tag` table: the
  tag list is a `GROUP BY` over `note_tag`.
- `revision`: every revision of every note, with parent revision ids in
  `revision_parent` (a DAG, like git commits) and tags in `revision_tag`.
  This history is what makes three-way merge possible.
- `device`: this device's id and name, and the last timestamp of its hybrid
  logical clock.
- `conflict`: notes whose revisions could not be merged automatically.
- `backend`: configured backends and their settings.
- `sync_read`: per backend, the files read in each device folder.
- `sync_segment`, `backend_revision`: this device's segments on each backend, and
  the revisions known to be there.

Schema version 2 (M3) adds these, except `backend`, which M4 adds.

"Delete forever" from trash writes a tombstone revision so the deletion syncs.
Tombstones stay until log compaction (deferred).

---

## 5. Merge rules

Each edit creates a new revision whose parent is the revision it edited. When a
device sees two "head" revisions of the same note (concurrent offline edits), it
merges them:

| Field | Rule |
|---|---|
| `body` and `title` | Three-way line merge (diff3) against the common ancestor revision. Non-overlapping edits merge cleanly, e.g. ticking item 3 on the phone and adding item 7 on the PC. Overlapping edits → **conflict** |
| `tags` | Set merge: tags added on either side are kept, tags removed on either side are removed. If one side adds a tag the other side removed, the add wins (OR-set semantics). Tags compare exactly and keep their case |
| `isPinned`, `state` | Last writer wins by stamp order (below). Each field's stamp changes only when a revision writes that field. An automatic merge carries the winning value with that value's original stamp, not its own |
| Delete vs. edit | The edit wins and the note comes back in the trash (state `TRASHED`), so nothing is silently lost |

**Stamps:** every revision has an HLC timestamp and a device id. Revisions are
ordered by HLC timestamp, then device id, then revision id, with ids compared
as unsigned byte strings. This one order is used wherever this section says
"higher" or "last writer". A device's clock does not take on a received
timestamp more than one day ahead of its wall clock, but a user revision is
always stamped above its parent (ADR-0003, refinements).

**Two kinds of revision:** a *user revision* (edit, pin, delete, conflict
resolution) gets the device's fresh HLC timestamp, strictly greater than its
parents', the device's id, and a UUIDv7 revision id. An *automatic merge* gets
a revision id that is a hash of its sorted parent ids and its content, and the
greatest of its parents' stamps. The device that computed it is not recorded,
so all of its fields derive from the parents alone. If two devices auto-merge
the same heads, they produce the same revision and it deduplicates. Two
different automatic merges can share a stamp, so the revision id is the final
tiebreaker. The one exception to a fresh timestamp is an imported note, whose
revisions carry the source times. Two imports give two roots. When two parents
have no common ancestor, then for each of `body` and `title` separately, if all
roots among the ancestors of the two parents being merged have the same value,
that value is the base; otherwise the base is empty. A parent counts as its own
ancestor. Parents with a common ancestor use it as usual
([ADR-0009](adr/0009-import-preserves-source-timestamps.md)).

**On conflict:** only `body` and `title` conflict. The device still writes a
deterministic automatic merge with both heads as parents. Tags and the
last-writer-wins fields merge by the rules above. Body and title come from the
head with the higher stamp, so every device picks the same one. The merge
records that it is conflicted and which parent lost. Every device that applies
it stores the losing text in `conflict`. The UI shows a badge and offers "keep
the shown version", "keep the other version", "keep both as separate notes" or
"combine by hand" (both versions in full, where they differ, and the text with
conflict markers pre-filled; see `app/shared-ui`). New edits while the conflict
is open take the conflicted merge as parent. Resolving creates a user revision
on top of the head that descends from the conflicted merge. It names the
conflicted merge it resolves, so other devices learn the conflict is closed.

**More than two heads:** a device folds the heads it has, sorted by revision
id (byte order), merging two at a time. Each step is a real automatic merge.
The fold stops at the first conflict; remaining heads stay heads until it is
resolved. This is deterministic for a given set of heads but not independent
of arrival order. Devices converge because they keep merging whatever heads are
present until one live head remains. A merge head whose user revisions another
head also has is covered and does not count; without that, devices out of step
would merge each other's merges for ever. When several best common ancestors
exist, the one with the highest stamp is the base. See ADR-0003 and
[ADR-0013](adr/0013-merge-base-and-covered-heads.md).

**Touching changes:** edits of adjacent lines merge, unlike in git and diff3,
and identical insertions count as one. The merge rules and the diff's search
limit are part of the sync format, because merged text is hashed. See
[ADR-0011](adr/0011-touching-changes-and-merge-format.md).

---

## 6. Sync protocol

### 6.1 BlobStore interface

```kotlin
/** A dumb file store. Implementations must not interpret file contents. */
interface BlobStore {
    suspend fun list(prefix: String): List<BlobInfo>
    suspend fun read(path: String): ByteArray
    /**
     * Must fail if [path] already exists. Files are immutable once written.
     * WebDAV implements this with `PUT` + `If-None-Match: *`.
     */
    suspend fun writeNew(path: String, bytes: ByteArray)
    suspend fun ping(): Reachability
}
```

`list(prefix)` matches whole path segments, recursively, and `ping` is reachable
only when the store itself is there: an unplugged drive never reads as an empty
store. The KDoc of `BlobStore` is the exact definition. The local folder needs a
marker file for that, written once when the backend is added, and it publishes
files without hard links on FAT
([ADR-0012](adr/0012-local-folder-publishing.md)).

A shared **contract test suite** in `backend/api/contract` runs against every
implementation. A new backend is "done" when it passes.

### 6.2 Layout on a backend

```
jotter/
├── format.json                        # protocol version
└── devices/
    ├── <deviceId-A>/
    │   ├── device.json                # name, platform, created
    │   ├── 000000000001-<hash>.json.gz  # change segment
    │   └── 000000000002-<hash>.json.gz
    └── <deviceId-B>/...
```

A **segment** is an immutable batch of revisions created on one device. Its name
is a number that rises per device, then a hash of its content: the first 16 bytes of
the SHA-256 of its JSON, as 32 lowercase hex digits. So a path has one possible
content, a restored device cannot overwrite anything, and a number used twice gives two
files, which are both read
([ADR-0014](adr/0014-sync-failure-handling.md)).

### 6.3 Sync cycle (per backend)

1. **Push:** write any local revisions not yet on this backend as a new
   segment under our own device folder. Re-pushing revisions that arrived from
   other devices is allowed, which lets backends relay between devices that
   never share a backend directly.
2. **Pull:** list every device folder, our own too. Read every segment not yet
   read. Names are compared, not numbers.
3. **Apply:** insert revisions (idempotent by revision id), recompute heads,
   merge (§5), update the `note` table and FTS, record what was read. All in one
   database transaction. What goes wrong with one segment stays with that segment
   ([ADR-0014](adr/0014-sync-failure-handling.md)).

Sync runs on app start, after edits (debounced), and periodically: on Android
via WorkManager (15 min minimum, network-constrained), on desktop via a
coroutine timer. Each backend syncs independently, and a backend that can't be
reached is skipped and retried with backoff.

### 6.4 The PC as hub

The Linux desktop app can run an optional **peer server** (the `server` module,
embedded or as a systemd user service). It exposes a BlobStore over HTTP, backed
by a folder on the PC, and is reachable over Tailscale. The phone adds it as a
backend like any other. If the PC is off, the phone keeps working, and both
converge on the next successful sync. The PC's own sync uses the same folder as a
local folder backend. Protocol, per-device tokens, the address rule that stands in
for TLS, limits and how it runs:
[ADR-0015](adr/0015-pc-hub-peer-protocol.md).

### 6.5 Adding and removing backends

- **Add:** a new backend is seeded by pushing the device's full revision
  history, then syncs normally.
- **Remove:** stops syncing and deletes the local sync records. Remote files stay
  unless the user explicitly chooses "also delete remote data".

### 6.6 Future encryption seam

Segments pass through a `SegmentCodec` (v1: JSON + gzip). End-to-end encryption
later becomes a codec (for example XChaCha20-Poly1305 with a passphrase-derived
key) without changing backends or the merge logic. `format.json` records the
codec. Documented as ADR-0007 in M6.

---

## 7. Interop

- **Google Keep import** (Takeout `Keep/*.json`): `title` and `textContent`
  map to the note. `listContent` becomes a Markdown task list. `labels` become
  tags. `isPinned`, `isArchived` and `isTrashed` map to state. Timestamps are
  preserved: the revisions of an imported note carry the original created and
  edited times as their HLC stamps
  ([ADR-0009](adr/0009-import-preserves-source-timestamps.md)). Colors are
  dropped. The import is idempotent: the note id is derived from the source
  note, and a note with any history, including a tombstone, is skipped, so
  re-importing doesn't duplicate notes. Tests run against a synthetic fixture set, never
  real user data.
- **Markdown export:** one `.md` file per note with YAML front-matter (tags,
  pinned, state, timestamps). Filter by tag. Available from the UI and the CLI
  (`jotter export --tag recipes ./out`).

---

## 8. Quality bar

- **Style:** ktlint with the `ktlint_official` code style and a 100-character
  line limit. detekt with strict thresholds (complexity, long methods, magic
  numbers). Android Lint warnings treated as errors.
- **Compiler:** Kotlin explicit API mode in every module. Compiler warnings are
  errors.
- **Docs:** KDoc on every public class, interface, function and property
  (detekt `UndocumentedPublic*` rules). Module-level `README.md` per module.
- **Coverage:** Kover gate at **95% line and branch coverage**. Generated code
  (SQLDelight), serialization DTOs in a `dto` package (plain data only) and
  thin platform entry points (Activity, `main()`) are excluded. Compose UI is covered with Compose UI tests. Kover cannot
  measure Kotlin/Native: native tests prove behaviour but do not count
  towards the gate.
- **Architecture decisions:** recorded as ADRs in `docs/adr/`. Planned:
  0001 local-first, 0002 SQLite/SQLDelight, 0003 revision DAG + diff3,
  0004 immutable segment sync, 0005 manual DI (all in M0); 0006
  native-compatible shared modules (M1); 0007 encryption plan and
  0008 multi-user plan (both deferred features, documented in M6); 0009
  [import keeps source timestamps](adr/0009-import-preserves-source-timestamps.md)
  (M2); 0011
  [touching changes and the merge format](adr/0011-touching-changes-and-merge-format.md)
  (M3); 0012 [local folder publishing](adr/0012-local-folder-publishing.md) (M3);
  0013
  [the merge base and covered heads](adr/0013-merge-base-and-covered-heads.md) and
  0014 [sync failure handling](adr/0014-sync-failure-handling.md) (M3); 0015
  [the PC as hub](adr/0015-pc-hub-peer-protocol.md) (M4).
- **CI (GitHub Actions):** format check, detekt, Android Lint, all tests
  (JVM + Robolectric + Kotlin/Native `linuxX64`), Kover verify, build APK
  and desktop binaries. Required for every merge.
  TODO: an emulator job (API 26 and 36) on pushes to `main` or nightly, running
  `connectedAndroidDeviceTest`. Until then the Android device tests of
  `core/sqlite` run by hand.
- **Reproducible F-Droid builds:** no Google Play Services, no proprietary
  dependencies.

---

## 9. Development model (agent swarm)

| Role | Who | Responsibility |
|---|---|---|
| Orchestrator / architect | Main Claude session | Owns this plan and the ADRs, splits milestones into tasks, defines module interfaces before implementation starts, merges |
| Implementer | One subagent per task, each in its own git worktree and branch | Writes code, tests and KDoc for exactly one task |
| Reviewer | A *different* subagent with fresh context for every commit | Reviews against the checklist below. Can approve or request changes. Never reviews its own work |
| Re-review | A new reviewer instance | Reviews the fixes after changes are requested, so no reviewer anchors on its earlier opinion |

**Per-commit flow:** task spec → implementer commits → CI passes locally →
reviewer subagent → (fix → fresh reviewer)* → approved → PR to `main` → CI green
→ merge.

**Reviewer checklist:** correctness and edge cases. Readability (would a human
understand this in one pass?). Naming. Fits the module boundaries and ADRs.
Tests are meaningful, not coverage padding. KDoc present and accurate. No dead
code, no premature abstraction.

**Maintainer involvement:** the maintainer reviews at the end of the project, not
at milestone boundaries (decided 2026-11-01). Each milestone still ends with a
demo note and a short summary of decisions made, in `docs/milestones/`.

Tasks inside a milestone that touch different modules run in parallel.

---

## 10. Milestones

| # | Milestone | Contents | Exit criteria |
|---|---|---|---|
| M0 | Foundation | Git repo + GitHub, LICENSE, Gradle KMP skeleton (version catalog, JDK 21 toolchain), ktlint/detekt/Kover/CI, ADR template + ADR-0001..0005, CONTRIBUTING. **Spikes:** bundled SQLite FTS5 on Android + JVM (including SQLDelight `fts5` dialect support); Compose Desktop packaging on Linux | CI green on an empty-but-wired project. Spikes documented |
| M1 | Core domain + storage | `core/model`, `core/storage`: notes, tags, pin/archive/trash, FTS search, revision table | 95% coverage. Search over 10k synthetic notes < 50 ms. Shared modules compile and test on `linuxX64` |
| M2 | Linux desktop + CLI + Keep import | `shared-ui` (note grid, editor with Markdown preview, tag filter, search, archive and trash views), `app/desktop`, `app/cli`, `keep-import`, `markdown-export`. Measure `jotter add` startup on the JVM ([ADR-0006](adr/0006-native-compatible-shared-modules.md)) | **Keep notes can be imported and the app used daily on Linux**. `jotter add` meets ADR-0006's 200 ms threshold, or a native CLI is decided in a new ADR |
| M3 | Sync engine | `core/merge` (diff3), `core/sync`, `backend/api` + contract tests, `backend/localfolder`, conflict UI | Property-based tests: N simulated devices with random offline edits always converge |
| M4 | WebDAV + PC hub | `backend/webdav` (tested against a containerised WebDAV server), `server` peer endpoint, backend management UI (add, remove, status, last sync) | Desktop ↔ mailbox.org ↔ second desktop converge |
| M5 | Android | `app/android`, Share-to target, WorkManager sync, F-Droid metadata and reproducible build, CI emulator job (API 26 + 36) running `connectedAndroidDeviceTest` (TODO from §8) | Phone and PC converge over Tailscale and over WebDAV |
| M6 | v1.0 hardening | ADR-0007 and ADR-0008, debug log export, error states, backup/restore docs (including how to recover a damaged segment: remove the backend on a device that holds its revisions and add it again, ADR-0014), user guide, release | Tagged v1.0 on GitHub, APK + AppImage/Flatpak |

**Later:** web app, E2E encryption, multi-user + login, Dropbox/Drive/S3,
Neovim/Emacs plugins, log compaction, home-screen widget.

---

## 11. Resolved decisions

| Question | Decision |
|---|---|
| Web app | Deferred to after v1 |
| Checklists | Markdown task lists (`- [ ] item`) with tappable checkboxes |
| Keep colors | Dropped on import |
| Coverage scope | 95% gate excludes generated code, serialization DTOs in a `dto` package (plain data only) and thin platform entry points |
| Name | Jotter |
| Repository | `jotter` on GitHub, public |
| License | Apache-2.0 |
