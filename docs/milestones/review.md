# Maintainer review: start here

This page is the entry point for your end-of-project review of M1 to M6
([PLAN.md §10](../PLAN.md#10-milestones)). AI agents wrote, reviewed and merged all of it.
No human has reviewed any code.

You gave three instructions in conversation that the repository does not record, except as
noted (entries e1 to e3):

- **2026-11-01**: do not pause at milestone boundaries; review everything at the end. The
  repository records only the end-of-project review, in PLAN.md §9 (commit `d47ffe2`).
- **2026-11-01**: "You take over the PRs please. Please use swarm to write code and review PRs."
- **2026-11-03**: "Please don't wait for my approval to merge the PRs in this repo."

State checked on 2026-11-07, about 21:00 UTC, with `gh` and `git` against `main` at `ac54ea5`.

## 1. What to do first

| # | Action | Where | State now |
|---|---|---|---|
| 1 | Run the mailbox.org check: desktop to mailbox.org to a second desktop. This is M4's exit criterion | [m4.md, checklist](m4.md#checklist-for-the-maintainer-mailboxorg) | Not run. M4 is merged but not closed. Nothing about mailbox.org's WebDAV has been tried |
| 2 | Run the phone and PC check: over Tailscale and over WebDAV. This is M5's exit criterion | [m5.md, checklist](m5.md#checklist-for-the-maintainer-phone-and-pc) | Not run. No real phone, tailnet or WebDAV server has been used from Android |
| 3 | Work through the before-v1.0 checklist: raise the version, dry run on `main`, AppImage on your desktop, signed APK on your phone, debug log, export and restore | [m6.md, checklist](m6.md#checklist-for-the-maintainer-before-tagging-v10) | Not started. `gradle.properties` says `0.1.0`, code `1`. `release.yml` has never run on `main` or a tag. No keystore exists |
| 4 | Close issues #28 to #32. [PR #34](https://github.com/sameerkrmishra/jotter/pull/34) fixed their two bugs; the orchestrator was not permitted to close them. [m6.md](m6.md#what-comes-after-m6) asks for a passing nightly on `7462101` or later first | [m6.md, issue #28](m6.md#issue-28-fixed-by-34) | All five open. The last nightly (2026-11-07 10:20 UTC) ran on `ac80a9d`, before #34, and failed. No nightly has run since. CI on `main` at `7462101` and `ac54ea5` was still running; `Reproducible` passed on `7462101` |
| 5 | Decide whether and when to tag v1.0. Tagging and publishing are yours alone: CI only makes a draft release | [RELEASING.md](../../RELEASING.md), steps 3 to 7 | No tag, no release, draft or published, on GitHub. Nothing is submitted to F-Droid |

Steps 1 and 2 also open the desktop app in a real window for the first time since M2.

## 2. Decisions made on your behalf

Every entry below comes from a "Decisions made" section of [m1.md](m1.md) to [m6.md](m6.md),
or from an ADR whose decision the agents made rather than PLAN.md. The five M0 ADRs
(0001 to 0005) were planned in PLAN.md, so only their later changes are listed. A few entries
come from PLAN.md or the build itself where the agents changed a rule without a milestone
entry; their source says so.

ADR-0007, ADR-0008 and every ADR from 0012 on are **Proposed**: written and reviewed by agents, not accepted by you.
ADR-0006, 0009, 0010 and 0011 say **Accepted**, but the architect agent accepted them
(see e18).

Flags:

- **(orchestrator)**: the milestone notes mark it as made by the orchestrator on your behalf.
  M1 and M2 predate that marking; M3 marks some entries; M4 to M6 mark nearly all.
- **Deviates**: differs from PLAN.md as first written (commit `022ed92`). The agents have
  since edited PLAN.md to match most of these, so PLAN.md today will not show the difference.
- **Fixed at 1.0**: part of the sync format, the on-disk or on-backend layout, or the release
  identity. Changing it after devices hold v1.0 data needs a new format version or a migration
  on every device, or cannot be done.

### (a) Data safety, sync and merge semantics, and on-disk formats (30)

Scrutinise these first. Most are hard or impossible to change after release.

| # | What was decided | Why | Source | Flags |
|---|---|---|---|---|
| a1 | androidx `sqlite-bundled` on the JVM as well as Android, behind Jotter's own SQLDelight driver | Same SQLite and FTS5 everywhere | [ADR-0002](../adr/0002-sqlite-via-sqldelight.md), [M1](m1.md#decisions-made) | Deviates (PLAN named xerial `sqlite-jdbc` on the JVM) |
| a2 | A stamp includes the revision id; each last-writer-wins field keeps its own stamp; the clock ignores received times more than a day ahead | Make ADR-0003's merge rules exact in `core:model` | [ADR-0003, refinements](../adr/0003-revision-dag-and-three-way-merge.md), [M1](m1.md#decisions-made) | Fixed at 1.0 |
| a3 | Every write is one `BEGIN IMMEDIATE` transaction; `seq` orders revisions locally; a file without Jotter's `application_id` is refused | App and CLI serialise writes and timestamps rise across processes; another SQLite file is left as it was | [`core:storage` README](../../core/storage/README.md), [M1](m1.md#decisions-made) | |
| a4 | Imported notes keep their Keep times as revision stamps; the note id is derived from the source; a note with any history is skipped; identical roots serve as merge base | Keep the original dates; re-import must not duplicate | [ADR-0009](../adr/0009-import-preserves-source-timestamps.md), [M1](m1.md#decisions-made), [M2](m2.md#decisions-made) | Fixed at 1.0. Exception to ADR-0003 |
| a5 | Edits on touching lines merge (unlike git and diff3); identical insertions count once; merge rules and the diff limit are part of the sync format | Ticking neighbouring checklist items must not conflict | [ADR-0011](../adr/0011-touching-changes-and-merge-format.md), [M3](m3.md#decisions-made) | Deviates (PLAN said diff3). Fixed at 1.0 |
| a6 | The local folder publishes by hard link, or rename under a lock file where there are none; a marker file is written only when the user adds the folder | `writeNew` never replaces a file, even on FAT; an unplugged stick never reads as an empty store | [ADR-0012](../adr/0012-local-folder-publishing.md), [M3](m3.md#decisions-made) | Fixed at 1.0 (marker) |
| a7 | Merge base is the highest-stamped best common ancestor, not recursive; a merge head whose user revisions another head has is "covered" and ignored; since #34 the fold repeats until nothing new | A recursive merge would fix much more format; without covering, devices merge each other's merges for ever | [ADR-0013](../adr/0013-merge-base-and-covered-heads.md), [M3](m3.md#decisions-made), [M6](m6.md#issue-28-fixed-by-34) | Fixed at 1.0 |
| a8 | A segment's name is its number plus a content hash; pull tracks names read, not numbers | A path has one possible content, so nothing can overwrite anything | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator); Deviates (PLAN had plain numbers and cursors). Fixed at 1.0 |
| a9 | Bad revisions are stored and relayed, never rejected; each is judged from itself and its ancestors | The verdict does not depend on arrival order, so devices agree | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator); Fixed at 1.0 |
| a10 | A device restored from backup keeps its id and reads unknown files in its own folder; a folder may have several writers | Its lost tail comes back; content names stop overwrites | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator); Deviates (amends ADR-0004's one writer per folder) |
| a11 | A note is capped at 5,000,000 UTF-16 units of title and body; a merge past it is a conflict | Editing cannot make a note that can never sync | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator); Fixed at 1.0 (affects merges) |
| a12 | Received merges are not derived again; a wrong text with a valid hash goes unnoticed | All your devices run the same code, so such a merge would be a bug that recurs anyway | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator) |
| a13 | Bit rot in a published file is a known limit, reported as `CORRUPT` each cycle; recovery is remove and re-add the backend | No way to fix a file in place under "never overwrite" | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator) |
| a14 | Saving or dismissing text that does not count is recorded on this device only | A synced dismissal needs a new kind of revision | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator) |
| a15 | Texts being combined by hand are kept locally (`combined_draft`), not synced | Survive a restart without a format change | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | (orchestrator) |
| a16 | A conflict answer names the head the user saw; nothing is written if the note shows another by then | A newer edit is never replaced unseen | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | |
| a17 | Hidden heads: a note never merges on partial history; unshown heads are listed until merged or settled | No device can know nobody holds the rest; no edit is hidden in silence | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | |
| a18 | Repairs: a device rewrites its own missing segment under the same name; a vanished file's revisions are pushed again | Lost files heal from any device that holds the revisions | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | |
| a19 | An empty listing from a reachable store is believed; the device then pushes everything | `BlobStore` promises it; the cost is duplicates, not loss | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | |
| a20 | A file is at most 32 MiB, compressed or inflated; pushes split, pulls apply in batches | A small file cannot fill memory | [ADR-0014](../adr/0014-sync-failure-handling.md), [M3](m3.md#decisions-made) | Fixed at 1.0 (older readers refuse bigger files) |
| a21 | `okio` gives SHA-256 and gzip in common code | One implementation on JVM, Android and Native | [`core:sync` README](../../core/sync/README.md), [M3](m3.md#decisions-made) | |
| a22 | The format file is `jotter/formats/1-json+gzip.json`; a backend with another version's file is `Incompatible` | Every path has one content; on Apache two versions could both write `format.json` | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator); Deviates (PLAN had `format.json`). Fixed at 1.0 |
| a23 | Adding a WebDAV store writes a test file twice and refuses a server that does not answer 412 | On such a server (e.g. `rclone serve webdav`) any retry could replace a file | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator) |
| a24 | WebDAV `list` is `PROPFIND` with `Depth: 1`, one collection at a time | Every server must support depth 1; lighttpd and Apache refuse `infinity` | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator) |
| a25 | `writeNew` is `PUT` with `If-None-Match: *` only; one winner among racing writers is not promised (Apache lets both win) | Sync does not need it, since a path has one possible content | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | |
| a26 | The PC syncs with the hub's folder directly, as a local folder backend | The PC is one more device on its own hub and relays to its other backends | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator) |
| a27 | Jotter never changes or replaces a damaged database; it points to a backup or a fresh start | Never make damage worse | [troubleshooting](../guide/troubleshooting.md), [M6](m6.md#decisions-made) | (orchestrator) |
| a28 | Export database is `VACUUM INTO`, checked, then renamed into place; there is no import, and no restore on Android | A consistent copy while the app runs, never over the live file | `NoteStore.exportTo`, `DatabaseExport`, [M6](m6.md#decisions-made) | (orchestrator) |
| a29 | A merge's id is the SHA-256 of canonical bytes: the domain `jotter-merge-v1\n`, then note id, parents, stamp, losing parent, content, title, body, tags, pinned and state, in that order and form | Every device must derive the same id for the same merge; `MergeHashTest` pins the bytes | [ADR-0013, canonical bytes](../adr/0013-merge-base-and-covered-heads.md), [M3](m3.md#decisions-made) | Fixed at 1.0 |
| a30 | Tags compare exactly and keep their case; normalisation trims and collapses a fixed list of whitespace characters, with no Unicode tables | Every platform derives the same tag sets and merge hashes | [ADR-0003, refinements](../adr/0003-revision-dag-and-three-way-merge.md), [M1](m1.md#decisions-made) | Fixed at 1.0 |

### (b) Security and secrets (17)

| # | What was decided | Why | Source | Flags |
|---|---|---|---|---|
| b1 | Secrets go to the Secret Service through `secret-tool` (stdin/stdout, no D-Bus autolaunch); a 0600 plain-text file only if the user chooses it | Fail closed; never in the database, a log, an argument or the environment | [ADR-0016](../adr/0016-where-secrets-live.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b2 | `secret-tool lookup` exiting 1 with no output is read as "locked or missing", and nothing is deleted because of it | `secret-tool` cannot tell the two apart | `SecretServiceStore`, [M4](m4.md#decisions-made) | (orchestrator); Not in ADR-0016 |
| b3 | Removing a backend deletes its secret first and stops if that fails; `--keep-secret` / "Remove anyway" override | No password left behind unseen, but a locked keyring cannot pin a backend for ever | [`app:cli` README](../../app/cli/README.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b4 | The hub speaks plain HTTP; the client sends its token only to loopback, `100.64.0.0/10` or `fd7a:115c:a1e0::/48`; the hub binds only to loopback or a `100.64.0.0/10` literal, IPv4 only | WireGuard (Tailscale) is the encryption; no TLS | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b5 | The hub uses JDK blocking sockets, one thread per connection; `server` is JVM-only | Ktor's raw sockets could not close a socket for real: 40 idle clients held ~500 MiB and ~700% CPU | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator); Deviates (PLAN: Ktor server). Amends ADR-0006 |
| b6 | At most 8 hub connections per address, 16 in all | One tailnet node cannot take every slot | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b7 | One token per device, made only on the PC and shown once; the hub stores only its SHA-256 and compares in constant time | No network pairing to attack; a lost phone is unpaired alone; the token file holds nothing worth stealing | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b8 | WebDAV: Basic auth on every request; up to 3 attempts on connection errors and 429/502/503/504; redirects only within the same scheme, host and port | The password never goes to another server | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b9 | WebDAV is `https` only, except `http` to loopback; a NAS on plain `http` is refused | Passwords and notes never cross a network in the clear | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator) |
| b10 | Android's Keystore key works after the first unlock; no user-authentication or unlocked-device requirement | WorkManager syncs while the screen is locked | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator) |
| b11 | Android 8 backs up nothing; from Android 9 only `jotter.db`, and only with client-side encryption | API 26 and 27 cannot require an encrypted backup | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator) |
| b12 | On Android the hub client is bound to the VPN network, with route checks and DNS on that network; OkHttp engine for it | The token never leaves by the mobile network or the carrier's DNS | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator) |
| b13 | Secret fields ask for a password keyboard without autocorrect | Keyboards must not learn or sync passwords | [`app:android` README](../../app/android/README.md), [M5](m5.md#decisions-made) | (orchestrator) |
| b14 | Logs keep exception messages only for an allow-list of classes; SLF4J TRACE dropped; Android's log in `noBackupFilesDir` | A pattern cannot recognise note text; Ktor puts URLs and headers in TRACE | [ADR-0021](../adr/0021-local-logging.md), [M6](m6.md#decisions-made) | (orchestrator) |
| b15 | Entering a secret again may move it between keyring and file: write new, record, then delete old | A restored database on a machine with no keyring could never take a password | [ADR-0016](../adr/0016-where-secrets-live.md) (amended by #26), [M6](m6.md#decisions-made) | (orchestrator) |
| b16 | Planned encryption: AES-256-GCM-SIV with a fixed nonce (deterministic), keyed names, a store key wrapped by Argon2id. Not built | Content naming, `writeNew` and repairs need identical bytes for identical segments | [ADR-0007](../adr/0007-end-to-end-encryption-plan.md), [M6](m6.md#decisions-made) | (orchestrator); Deviates (PLAN's example was XChaCha20-Poly1305) |
| b17 | Planned: an existing store is never encrypted in place; the device database, exports and logs stay plain | In place is impossible without overwriting files, and plain copies stay in the backend's backups; disk encryption is the OS's job | [ADR-0007](../adr/0007-end-to-end-encryption-plan.md), [M6](m6.md#decisions-made) | (orchestrator) |

### (c) Public surface: CLI, file formats, release and signing, F-Droid (15)

| # | What was decided | Why | Source | Flags |
|---|---|---|---|---|
| c1 | The CLI ships its own start script with a class data archive | Faster start on the JVM, towards ADR-0006's 200 ms threshold | [`app:cli` README](../../app/cli/README.md), [M2](m2.md#decisions-made) | |
| c2 | The hub runs inside the desktop app, on port 47734 by default; no systemd user service, no `jotter hub` | No second process or service file | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator); Deviates (PLAN §6.4 offered a systemd service) |
| c3 | Hub protocol: one request per connection, no retries or redirects in the client | Every limit stays per connection; sync already retries | [ADR-0015](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | (orchestrator) |
| c4 | emoji2's font provider is removed from the APK | It asks Google Play Services for a font, which F-Droid may question | [docs/fdroid](../fdroid/README.md), [M5](m5.md#decisions-made) | (orchestrator) |
| c5 | R8 is off for v1; the APK is about 16 MB | Shrinking needs keep rules and device tests of a release build | [ADR-0019](../adr/0019-release-builds.md), [M5](m5.md#decisions-made) | (orchestrator) |
| c6 | The version is two literals in `gradle.properties` | F-Droid's update checker reads them with a regex; anything computed breaks reproducibility | [ADR-0019](../adr/0019-release-builds.md), [M5](m5.md#decisions-made) | (orchestrator) |
| c7 | Release signing is outside the repository (`JOTTER_KEYSTORE`, `JOTTER_KEY*`); CI and F-Droid build unsigned; the build refuses the configuration cache when signing | Your key never reaches CI; F-Droid can verify your signed APK against its own build | [ADR-0019](../adr/0019-release-builds.md), [RELEASING.md](../../RELEASING.md), [M5](m5.md#decisions-made) | (orchestrator); Fixed at 1.0 (the key you sign v1.0 with) |
| c8 | The JDK is pinned to major version 21, not a vendor | CI builds with Temurin and Zulu to show it does not matter | [ADR-0019](../adr/0019-release-builds.md), [M5](m5.md#decisions-made) | (orchestrator) |
| c9 | An AppImage, not a Flatpak | Flatpak's sandbox breaks path-typed folder backends, splits app and CLI data, needs keyring grants | [ADR-0020](../adr/0020-linux-desktop-package.md), [M6](m6.md#decisions-made) | (orchestrator); PLAN allowed either |
| c10 | The CLI is inside the AppImage, chosen by the name `jotter` or a first argument `cli`, and also shipped alone as a zip | One download; the zip starts faster (~146 ms vs ~450 ms) | [ADR-0020](../adr/0020-linux-desktop-package.md), [M6](m6.md#decisions-made) | (orchestrator) |
| c11 | AppImage `type2-runtime` pinned by SHA-256, packed with mksquashfs; no update information; pinned `ubuntu-24.04` runner | Reproducible builds; the app contacts nothing but its backends | [ADR-0020](../adr/0020-linux-desktop-package.md), [M6](m6.md#decisions-made) | (orchestrator) |
| c12 | `release.yml` makes a draft release only | Signing and publishing stay with you | [RELEASING.md](../../RELEASING.md), [M6](m6.md#decisions-made) | (orchestrator) |
| c13 | The hub's wire protocol: HTTP/1.1, `GET /jotter-peer/v1/ping`, `GET .../list?prefix=P`, `GET` and `PUT .../blob?path=P`, paths in the query string, every answer carrying `Jotter-Peer-Protocol: 1` | One protocol file both sides use; a later version goes under `/v2/` | [ADR-0015, transport and protocol](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | Fixed at 1.0 (paired phones speak v1) |
| c14 | A pairing token is `<id>.<secret>`: 32 hex digits naming the device, then 32 random bytes in unpadded base64url, sent as `Authorization: Bearer` | 244 bits cannot be guessed, so the hub can keep a plain SHA-256 | [ADR-0015, authentication](../adr/0015-pc-hub-peer-protocol.md), [M4](m4.md#decisions-made) | Fixed at 1.0 (existing pairings) |
| c15 | The desktop entry and AppStream metadata are named `io.github.sameerkrmishra.jotter`, the same as the Android `applicationId` | One reverse-DNS id for the app | [ADR-0020](../adr/0020-linux-desktop-package.md); Android: [`app/android/build.gradle.kts`](../../app/android/build.gradle.kts), recorded in no ADR | Fixed at 1.0 (Android updates, F-Droid and menu entries key on it) |

### (d) Product and UX (8)

| # | What was decided | Why | Source | Flags |
|---|---|---|---|---|
| d1 | The editor autosaves 5 s after typing stops, and at least every 60 s; Markdown links and images never touch the network | Little lost on a crash; no network from viewing a note | [`app:shared-ui` README](../../app/shared-ui/README.md), [M2](m2.md#decisions-made) | |
| d2 | An unreachable local folder is retried every 30 s; other backends back off 30 s to 30 min; a refused password waits 30 min | A plugged-in USB stick should sync within 30 s | [`app:sync` README](../../app/sync/README.md), [M4](m4.md#decisions-made) | (orchestrator) |
| d3 | A backend that comes back syncs at once, without waiting for the 5-minute round | Faster convergence | [`app:sync` README](../../app/sync/README.md), [M4](m4.md#decisions-made) | (orchestrator) |
| d4 | `UNAUTHORIZED` is its own reason, not `FAILED` | Stop retrying a password that cannot work; ask for a new one | [ADR-0017](../adr/0017-webdav-backend.md), [M4](m4.md#decisions-made) | (orchestrator) |
| d5 | No local folders on Android in v1 (no USB stick, SD card or Syncthing folder) | `content://` storage lacks hard links, locks and a safe rename, which ADR-0012 rests on | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator); Deviates (PLAN §1 listed local folders for all platforms) |
| d6 | One sync scheduler per Android process; `SyncWorker` always succeeds | WorkManager's backoff must not stack on the scheduler's | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator) |
| d7 | Planned sharing: separate "spaces", keys wrapped to each member, no accounts or login, one person per device database. Not built | Works on the same dumb backends, with no server that decides; a shared device uses the system's user profiles | [ADR-0008](../adr/0008-multi-user-plan.md), [M6](m6.md#decisions-made) | (orchestrator); Deviates (PLAN's "Later" lists "multi-user + login") |
| d8 | v1 has no "also delete remote data" when removing a backend; the user deletes files by hand | Recorded only as a PLAN.md edit (commit `6d66d30`), without a reason | [PLAN.md §6.5](../PLAN.md#65-adding-and-removing-backends) | Deviates (PLAN offered the option) |

### (e) Process, build and CI (18)

| # | What was decided | Why | Source | Flags |
|---|---|---|---|---|
| e1 | No pause at milestone boundaries; you review everything at the end | Your instruction in conversation, 2026-11-01. The repository records only the end-of-project review | [PLAN.md §9](../PLAN.md#9-development-model-agent-swarm), [CONTRIBUTING.md](../../CONTRIBUTING.md), commit `d47ffe2` | Deviates (PLAN and CONTRIBUTING promised per-milestone review) |
| e2 | The orchestrator takes over the PRs, with a swarm of agents writing code and reviewing PRs | Your instruction in conversation, 2026-11-01: "You take over the PRs please. Please use swarm to write code and review PRs." | Not recorded in the repository; [PLAN.md §9](../PLAN.md#9-development-model-agent-swarm) describes the swarm | |
| e3 | The orchestrator merges PRs without waiting for your approval | Your instruction in conversation, 2026-11-03: "Please don't wait for my approval to merge the PRs in this repo." | Not recorded in the repository | |
| e4 | PRs merged with merge commits, not rebased or squashed | Stacked branches held merge commits a rebase would rewrite | [M4](m4.md#decisions-made), [M5](m5.md#decisions-made), [M6](m6.md#decisions-made) | (orchestrator) |
| e5 | The coverage gate uses Kover's JaCoCo engine on a JVM-only variant | Kover's default engine counts Compose's unreachable branches | [build-logic README](../../build-logic/README.md#coverage), [M2](m2.md#decisions-made) | |
| e6 | Coverage exclusions grew: serialization `dto` packages, the CLI and desktop `main`, and Android glue including the Keystore key and the Tailscale VPN check | These need generated code or an Android runtime; device tests cover the glue but do not count | [PLAN.md §8](../PLAN.md#8-quality-bar), [`jotter.root`](../../build-logic/src/main/kotlin/jotter.root.gradle.kts) | Deviates (PLAN §11 excluded only generated code and thin entry points) |
| e7 | Shared modules compile and test on `linuxX64` | Keep a native CLI cheap | [ADR-0006](../adr/0006-native-compatible-shared-modules.md), [M1](m1.md#decisions-made) | Not in first PLAN |
| e8 | The CLI stays on the JVM; no native CLI | It meets ADR-0006's threshold | [ADR-0006](../adr/0006-native-compatible-shared-modules.md), [M2](m2.md#decisions-made) | |
| e9 | `app:shared-ui` builds for JVM and Android only | Compose has no `linuxX64` variant; `core/*` must never depend on the UI | [ADR-0010](../adr/0010-compose-ui-outside-linuxx64-rule.md), [M2](m2.md#decisions-made) | |
| e10 | A logger of the project's own, not Kermit; one process-wide `Logging` | Kermit has no file sink; an exception to ADR-0005's injection | [ADR-0021](../adr/0021-local-logging.md), [M6](m6.md#decisions-made) | (orchestrator); Deviates (PLAN named Kermit) |
| e11 | `check` is the only required status check; the 3000-seed run is a separate `simulate` job | Keep `check` near its length | [ci.yml](../../.github/workflows/ci.yml), [M3](m3.md#decisions-made) | (orchestrator); Deviates (PLAN §8: CI, including APK and desktop builds, required for every merge) |
| e12 | CI's lighttpd is Debian's 1.4.79 from `snapshot.debian.org`, in a digest-pinned image | No official image; Alpine dropped the build | [`backend:webdav` README](../../backend/webdav/README.md), [M4](m4.md#decisions-made) | (orchestrator) |
| e13 | Device tests run on every PR, on API 26 and 36, but are not required | Public repo, free minutes, finish before `check` | [ci.yml](../../.github/workflows/ci.yml), [M5](m5.md#decisions-made) | (orchestrator) |
| e14 | `check`'s timeout is 60 minutes (was 45) | M4's run took 30 m 44 s; it now takes about 38 m | [ci.yml](../../.github/workflows/ci.yml), [M5](m5.md#decisions-made) | (orchestrator) |
| e15 | `Reproducible` runs on PRs only when they touch the build | Two release builds are costly; app code alone rarely breaks determinism | [`reproducible.yml`](../../.github/workflows/reproducible.yml), [M5](m5.md#decisions-made) | (orchestrator) |
| e16 | Android tests reach a host hub through `adb reverse`, with no test exception to the address rule | Keep the security rule untouched in tests | [ADR-0018](../adr/0018-android-app.md), [M5](m5.md#decisions-made) | (orchestrator) |
| e17 | A device-test module that reported no test case runs once more; failed tests are never retried | An `adb` failure once passed a module with 0 tests | `.github/scripts/device-tests.sh`, [M6](m6.md#decisions-made) | (orchestrator) |
| e18 | The architect agent marked ADR-0006, 0009, 0010 and 0011 Accepted | Commit `69b7a4f`: "reviewed and accepted by the architect"; the others give no reason | Commits `69b7a4f`, `1248895`, `627c326`; [ADR index](../adr/README.md) | Your acceptance is still pending |

## 3. Known risks and gaps

Only what still holds on `main` today. Items later fixed (Android device runs of M1 to M4
modules, M2's packaging gap, M4's SLF4J warnings, the M3 nightly that never ran) are left out.

**Never run on real hardware or accounts**

- Any real WebDAV provider (mailbox.org, Nextcloud, a NAS). Every WebDAV test is plain
  `http` to loopback; **TLS has never been exercised**, on Linux or Android.
- A real tailnet, Tailscale's Android app, MagicDNS. All hub tests are on loopback or
  `adb reverse`.
- A real keyring (GNOME Keyring, KWallet, KeePassXC) or an unlock prompt.
- A real phone. Screens were seen only on a 1080 × 1920 emulator. Doze, App Standby, Auto
  Backup on Android 9 to 11, Google's backup, device transfer and the document picker on a
  phone are untested.
- The desktop app in a real window since M2 (only Xvfb). HiDPI, IME and FUSE-less machines
  untested. It runs under XWayland.
- Real FAT/exFAT, NFS or FUSE mounts; a real Google Takeout.
- A signed release, `apksigner verify`, `apksigcopier compare`; `fdroid lint`, `build` and
  `scanner`. The F-Droid recipe still has TODOs, and the listing has no screenshots.

**Testing gaps**

- No nightly has passed. The one after #34 has not run yet.
- An API 26 device-test failure on `main` after #22 was treated as a flake and not
  investigated ([m6.md](m6.md)).
- Mutation results in `core/sync/scripts/mutants.py` are not committed; one mutant ("lost
  file stays read") was never checked ([m3.md](m3.md#known-limitations-and-what-was-not-verified)).
- Timings come from one workstation. `check` takes about 38 minutes and grows.

**By design, in v1**

- No end-to-end encryption: anyone who can read a backend, the hub folder or a lost USB
  stick reads every note and its history.
- The hub is plain HTTP inside WireGuard. `100.64.0.0/10` is also carrier NAT space, and on
  Android another app's VPN in that range passes the Tailscale check.
- A merge with wrong text and a valid hash goes unnoticed; a decayed file is reported on
  every cycle; invalid revisions are kept for ever. Cost grows with history (no compaction).
  A criss-cross history conflicts more often than under a recursive merge.
- Apache can let two writers of one path both win; the `If-None-Match` probe runs once.
- A chosen secrets file is plain text, and over SSH or cron it is the only choice.
- The debug log can show folder paths outside home; redaction is pattern-based.
- Android: no local folders; nothing backed up on Android 8; no sync before the first
  unlock; WorkManager's 15-minute floor; switching between the debug and a release build
  means uninstalling, which deletes notes; it cannot load an exported copy back.
- A restored database never brings passwords or tokens; a phone must pair again.
- The AppImage is x86_64 only, needs FUSE, is not sandboxed and has no updates.
- The CLI has no edit, pin, archive or trash commands and cannot answer conflicts; a repeated
  Keep import skips newer Keep edits; attachments are dropped. The hub is IPv4 only and
  pairing means typing a long token.

Full lists: [M1](m1.md#known-limitations-and-what-was-not-verified),
[M2](m2.md#known-limitations-and-what-was-not-verified),
[M3](m3.md#known-limitations-and-what-was-not-verified),
[M4](m4.md#known-limitations-and-what-was-not-verified),
[M5](m5.md#known-limitations-and-what-was-not-verified),
[M6](m6.md#known-limitations-and-what-was-not-verified).

## 4. How the work was checked

- **Review.** AI only. Each change was reviewed by a fresh reviewer agent, and a new one for
  each round of fixes ([PLAN.md §9](../PLAN.md#9-development-model-agent-swarm)). Those
  reviews happened outside GitHub: no review or comment is recorded on the pull requests.
  `main` requires the `check` status and no approving review.
- **CI** ([ci.yml](../../.github/workflows/ci.yml)) on every PR and push to `main`: `check`
  (build, JVM, Android host and `linuxX64` tests, ktlint, detekt, Android Lint, coverage
  gate), `simulate` (sync simulator, seeds 1 to 3000), `webdav` (lighttpd, Apache and rclone in
  containers), `android-device` (API 26 and 36 emulators). Only `check` is required.
  [`reproducible.yml`](../../.github/workflows/reproducible.yml) builds the APK and the
  AppImage/CLI zip twice and compares bytes.
- **Simulator.** Up to 5 devices plus restored backups, 1 to 3 in-memory backends, random
  edits, crashes and faults, asserting full convergence and no lost line. 800 seeds in
  `check`, 3000 in `simulate`, 50,000 a night in [`nightly.yml`](../../.github/workflows/nightly.yml).
  Its first five nights all failed and found two real bugs, fixed in #34. No real backend,
  app or UI code runs in it ([m3.md](m3.md#what-it-does-not-cover)).
- **Coverage gate.** 95% line and branch, JVM classes only. Native and device tests do not
  count, and the exclusions in e6 apply.
- **Never run** on your hardware or accounts: see section 3. Each milestone's exit criterion
  from M4 on is unmet until you run its checklist.
