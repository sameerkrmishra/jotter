# 0018. The Android app: no local folders, Keystore secrets, WorkManager sync and the tailnet

- **Status:** Proposed
- **Date:** 2026-11-04

## Context

M5 adds `app/android` ([PLAN.md §10](../PLAN.md#10-milestones)): an Activity around
`app:shared-ui` ([ADR-0010](0010-compose-ui-outside-linuxx64-rule.md)), a "Share to" target,
and background sync. Four questions are Android's own:

- **Local folders.** `backend:localfolder` works on a path in the file system
  ([ADR-0012](0012-local-folder-publishing.md)). On Android 10 and later an app reaches
  shared storage, SD cards and USB drives only through the Storage Access Framework, as
  `content://` documents. A document provider has no hard links, no `fcntl` locks on the
  store's files, no rename that refuses an existing target, and no way to tell an unplugged
  drive from an empty folder. ADR-0012's guarantees rest on exactly those.
- **Secrets.** [ADR-0016](0016-where-secrets-live.md) left Android's store to M5: WebDAV app
  passwords and hub tokens must never be in the database, a log, a backup or an export.
- **Sync.** Android stops background processes, freezes cached apps (Android 14 and later),
  and runs background work through WorkManager, at most every 15 minutes, while `app:sync`'s
  `SyncScheduler` was written for a desktop process that lives as long as its window
  (PLAN.md §6.3).
- **The tailnet.** The hub client sends its token, in plain HTTP, only to loopback and
  Tailscale's ranges ([ADR-0015](0015-pc-hub-peer-protocol.md)). On a phone, `100.64.0.0/10`
  is also the carrier-grade NAT range of many mobile networks: with Tailscale off, such an
  address can be a stranger's on the carrier's network.

## Decision

**No local folders on Android in v1.** The Sync screen has no folder form there; it says, in
plain words, that Android gives apps no plain access to shared storage or USB drives and that
Jotter does not use Android's folder picker yet, and offers WebDAV and the PC hub
(`SyncControls.foldersUnavailable`). A folder inside the app's own storage would be useless:
no other device can reach it. The backend manager itself is unchanged and can still open a
folder (device tests use one in the app's cache). A `LocalFolderConfig` that arrives in a
database restored from Linux shows as not found, like an unplugged stick. A SAF backend would
need its own publishing rules, a new ADR and its own contract test run; it is left for later.

**Secrets are sealed with a Keystore key** (`SealedFileSecretStore`, `AesGcmCipher`,
`keystoreKey`), behind ADR-0016's `SecretStore`:

- One AES-256 key in the Android Keystore (`jotter-backend-secrets`), for GCM only, made on
  first use. Its bytes never leave the Keystore, which may keep it in hardware. It is made
  without `setUserAuthenticationRequired` or `setUnlockedDeviceRequired`, so it is usable
  whenever the phone has been unlocked once since it booted: before that, Android runs no
  app that is not direct-boot aware, which Jotter is not, and its files are still encrypted
  anyway. That is needed for background sync, which WorkManager runs while the screen is
  locked, often for hours; a key that needs an unlocked screen would fail every such sync.
  The cost: on a phone that was unlocked once and then stolen while locked, the key works
  for whoever can run code as Jotter, which Android's sandbox and a locked screen prevent.
- Each secret is encrypted with a fresh IV that the Keystore chooses, with its name
  (`<backend id>/<kind>`) as associated data, so a sealed secret cannot be moved to another
  backend. The sealed secrets are in one JSON file in `noBackupFilesDir`, which no backup or
  device transfer copies, written whole to a new file, synced and renamed.
- A secret the key cannot open, as after the Keystore lost its key, reads as no secret: the
  user enters it again, as after a restore elsewhere. A damaged file is never overwritten.
- Android has no keyring and no plain-text choice, so both of ADR-0016's places are this one
  store. Nothing logs; no message holds a secret.

**Backups.** Auto Backup and device transfer copy the notes database (`jotter.db` and its
`-wal`) and nothing else: no secrets, settings or WorkManager state. A cloud backup includes it
only if it is encrypted with the screen lock (`disableIfNoEncryptionCapabilities` on Android
12 and later, `clientSideEncryption` on 9 to 11, in `res/xml-v28/backup_rules.xml`). Android
8.0 and 8.1 (API 26 and 27) cannot require that, so there the default
`res/xml/backup_rules.xml` excludes everything: nothing of Jotter's is backed up. A restored
phone keeps its device id, which sync handles ([ADR-0014](0014-sync-failure-handling.md)),
and asks for its passwords again.

**Sync** shares `app:sync`'s scheduler: one `SyncScheduler` per process, in the composition
root (`AppGraph`), running on `Dispatchers.Default` with the database and network on
`Dispatchers.IO`, never on the main thread.

- It starts on first use: its first round is the sync on start. Each time the Activity comes
  to the screen, another round. Edits sync 5 s after typing stops while the process runs,
  "Sync now" at once, as on the desktop. Its own period is 15 minutes.
- WorkManager's `SyncWorker` runs every 15 minutes with a network connection, while at least
  one backend exists (the job is cancelled when the last one is removed), and once, with a
  network, after the app leaves the screen or a text is shared, so that edits leave the phone
  even if Android freezes or stops the process first. A worker asks the scheduler for a round
  with `SyncScheduler.syncAndWait` and returns when that round has ended.
- So there is one sync at a time per database (one process, one scheduler, and
  `BackendManager`'s own lock), and the backoff, the 30-minute wait after a refused password
  and the reporting are the scheduler's. A worker always succeeds, so that WorkManager's
  backoff does not stack on top.
- Leaving the screen also stores the editor's draft and the review's combined texts
  (`AppViewModel.keep`), as the process may end without warning.

**The hub's token goes out only while Tailscale carries the app** (`TailnetCheck` in
`app:sync`, `AndroidTailnet` here). Before a hub is opened, Android is asked whether this
app's network is a VPN holding an address in `100.64.0.0/10` or `fd7a:115c:a1e0::/48`, as
Tailscale's Android app is. If not, the hub is unreachable with a plain reason, at adding,
syncing and on the status screen, and nothing is sent. Loopback is always allowed. Otherwise
the hub client gets an HTTP engine of the app's (`TailnetCheck.engine`; Ktor's OkHttp engine,
as CIO takes no socket factory) whose sockets are bound to that VPN network with
`Network.bindSocket` before they connect, and connect only to an address that the VPN's routes
(`LinkProperties.routes`) cover, read again at each connection. So if the VPN goes away in the
middle of a sync, requests fail instead of leaving by the mobile network, and an address that
Tailscale does not route is never connected to. The engine uses no proxy, retries nothing and
follows no redirect, like the client's own. Tailscale's
MagicDNS names resolve only while its VPN runs, and an app that its split tunnelling leaves
out sees no VPN and so sends nothing. The desktop passes `TailnetCheck.NONE`: ADR-0015's rule
is unchanged there.

**Tests reach the host through `adb reverse`.** `10.0.2.2`, the emulator's name for the host,
is a LAN address to the hub client and stays refused, with no test exception. The end-to-end
test (`app/android/scripts/hub-e2e.sh`) forwards the emulator's `127.0.0.1:PORT` to the
host's, where `app:sync`'s `HubForAndroidTest` runs a hub and the PC's own sync.

**The Share target** takes `ACTION_SEND` of `text/plain` and `text/markdown`. The subject, such
as a web page's title, becomes the title on one line, unless it repeats the text. A file or an
image alone is refused in plain words; beside text, it is left out and named. Text over a
note's cap (`MAX_NOTE_CHARS`) is refused whole, never cut. A share's intent travels in one
binder transaction of at most 1 MB, so Android itself refuses a text of about 330,000
characters or more before Jotter sees it; 110,000 are tested. An extra that cannot be
unparcelled refuses the share plainly instead of crashing. The saved note's id is kept in the
window's saved state, so a window brought back after Android ended the process does not store
the note again; the intent's extras are not copied into that state.

## Consequences

### Positive

- The phone syncs with WebDAV and the PC hub, in the background, with the desktop's rules for
  backoff and refused passwords, from one implementation.
- A secret is useless off the phone: it is not in any backup, and its key cannot leave the
  Keystore.
- A phone on a carrier's CGNAT network never sends its token there, even to an address that
  the client's rule allows.
- The emulator tests need no exception to the address rule.

### Negative

- No USB stick, SD card or Syncthing folder on Android in v1.
- Sync after edits relies on the process living 5 s after typing stops, or on the job when
  the app leaves the screen; Android may delay that job (Doze, App Standby buckets).
- Every 15 minutes is WorkManager's floor, and Android may stretch it.
- **A VPN of another app that uses an address in `100.64.0.0/10` passes the check, and its
  routes then carry the token.** Android does not tell an app which app runs a VPN, so Jotter
  cannot tell such a VPN from Tailscale's. This is a stated limit, not a protection.
- On Android 8.0 and 8.1 nothing is backed up.
- Two HTTP engines in the app: CIO for WebDAV and loopback, OkHttp for the hub on the tailnet.
- The end-to-end script runs by hand: CI's `android-device` job runs the device tests, not
  the PC's half on the host.

## Alternatives considered

- **Local folders through SAF in v1:** what Syncthing users would want, but it needs a
  publishing scheme without links, locks or a refusing rename, and a way to tell an unplugged
  drive from an empty one. Too much for v1 without a design of its own.
- **Folders in the app's own storage, or with `MANAGE_EXTERNAL_STORAGE`:** the first is
  unreachable by other devices; the second is refused by F-Droid's and Play's policies for a
  notes app, and still has no locks on FAT.
- **Jetpack Security's `EncryptedSharedPreferences`:** deprecated, and a dependency for what
  forty lines do.
- **A key that needs the screen unlocked:** safer at rest, but background sync would fail
  whenever the phone is locked.
- **Only WorkManager, no in-process scheduler:** every edit and "Sync now" would become a
  job, with WorkManager's delays, and the backoff would need storing between jobs.
- **Only the in-process scheduler:** no sync once Android freezes the app, and none in the
  background at all.
- **Allowing `10.0.2.2` in debug builds:** a test-only hole in the one rule that keeps the
  token on the tailnet.
- **No tailnet check, as on the desktop:** a phone moves between networks, and its carrier
  may use `100.64.0.0/10`; ADR-0015 already named the risk.
