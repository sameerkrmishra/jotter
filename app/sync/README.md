# app:sync

What the apps share to sync: the list of backends and how each is opened, adding and
removing them, running and recording a sync, the status in plain words, and when the
desktop app syncs ([PLAN.md](../../docs/PLAN.md) §6.3 and §6.5). The desktop app uses
it through `app:shared-ui`, the CLI directly. It is a `jotter.kmp-library` module, so
it builds and tests on `linuxX64` too: the CLI needs it, and ADR-0010 keeps such logic
out of `app:shared-ui`.

```kotlin
val engine = SyncEngine(store, DeviceInfo("Linux", "linux"), Dispatchers.IO)
val secrets = linuxSecretStores(System::getenv, System.getProperty("user.home"), Dispatchers.IO)
val manager = BackendManager(store, engine, StandardBackendFactory(secrets), secrets, Dispatchers.IO)
when (val result = manager.addLocalFolder("/media/stick")) {
    is AddResult.NotAStoreYet -> // ask the user, then:
        manager.addLocalFolder("/media/stick", setUpNewStore = true)
    else -> Unit
}
manager.syncAll()                       // one cycle per backend, recorded in the database
SyncScheduler(manager::syncAll).start(scope, store.notes.observeRevisionMark())
```

**Dependency rule:** `core:storage`, `core:sync`, `backend:api` (all in its API) and
each backend implementation it can open: `backend:localfolder`, `backend:webdav` and
`backend:peer`. No UI. Its JVM tests use the `server` module's hub.

## API

| Type | What it is |
|---|---|
| `BackendConfig`, `LocalFolderConfig`, `WebDavConfig`, `PeerConfig`, `UnknownConfig` | Where a backend is and what secrets it needs. Stored as `kind` and JSON `settings` in the `backend` table, never with a secret. `UnknownConfig` is a kind this version cannot read, listed so that it can be removed, never synced |
| `Backend`, `Attempt`, `Outcome`, `BackendSync`, `BackendStatus` | A configured backend, how its last sync went, what one sync did, and its reachability now |
| `BackendFactory`, `StandardBackendFactory`, `MissingSecretException` | Opens the `BlobStore` of a backend, reading its password |
| `BackendManager`, `AddResult`, `PasswordChange` | Adds a local folder or a WebDAV server, replaces a password, removes a backend, syncs one or all, and reports status |
| `SyncScheduler`, `SyncTiming` | When the desktop and Android apps sync |
| `SecretStore`, `Secret`, `SecretKey`, `SecretKind`, `SecretPlace`, `SecretStores`, `NoSecretStore`, `SecretStoreException`, `SecretStoreUnavailableException` | Where backend secrets live ([ADR-0016](../../docs/adr/0016-where-secrets-live.md)) |
| `SecretServiceStore`, `SecretFileStore`, `linuxSecretStores` | JVM only: the keyring through `secret-tool`, and the 0600 file the user may choose |
| `describe(Reachability)` | Reachability in plain words |
| `TailnetCheck` | Whether a hub's token may leave the device now, beyond the address rule (Android: only while Tailscale runs) |

## Adding a kind of backend

WebDAV and the PC hub plug in here, each in four places:

1. a `BackendConfig` class with its settings, and the `SecretKind`s it needs;
2. its settings as a `@Serializable` class in the `dto` package;
3. a branch in `BackendKinds` (`kindOf`, `settingsOf`, `configOf`), under a new kind
   name such as `webdav`;
4. a branch in the backend factory that builds its `BlobStore`, reading its secret
   from the `SecretStore`.

Then an `add` function in `BackendManager` for what adding one involves (for WebDAV,
storing the app password, then checking that the server answers), and the forms in the
apps. An older version lists a backend of a newer kind as `UnknownConfig` and never
syncs it.

## WebDAV and secrets

- **Adding a WebDAV server** pings the collection with the password. A store is
  added; a collection that is not one yet answers `NotAStoreYet`, and only a second
  call with `setUpNewStore` runs `initialize()`, which refuses a server that ignores
  `If-None-Match` (ADR-0017). Plain `http` is refused except to loopback. A missing
  collection, a refused password or an unreachable server is `Refused` with the
  reason in plain words. The password is stored before the backend's row, under
  the new id, and deleted again if setting up or the row fails. Where no keyring
  answers, nothing is added and the result is `NoKeyring`: the app offers the file
  and calls again with `SecretPlace.FILE`.
- **Where a password is** is a setting of the backend (`secrets: keyring|file` in its
  JSON), never the password. `whereSecretsAre` says it for the status screen.
- **A refused password** (`UnreachableReason.UNAUTHORIZED`, at `ping` or in the middle
  of a cycle) ends a sync as `Outcome.UNAUTHORIZED`, which says to make a new app
  password. The scheduler then waits its longest backoff, 30 minutes, instead of
  retrying quickly; "Sync now" still tries at once. A password missing from its store,
  as after restoring the database elsewhere, is `UNAUTHORIZED` too.
  `changePassword` checks a new password with the server before storing it.
- **Removing** deletes the passwords first: if the keyring cannot be reached, nothing
  is removed and the `SecretStoreException` says why, so no password is left behind.
- **`SecretServiceStore`** runs `secret-tool` with the secret on stdin (`store`) or
  stdout (`lookup`), never in an argument or the environment, with
  `DBUS_SESSION_BUS_ADDRESS` set to the session's bus or to `disabled:`, so it fails
  closed and never starts a bus or keyring of its own. No `secret-tool`, no bus or no
  provider is `SecretStoreUnavailableException`. Only a `clear` that ends with 0
  counts as deleted; one that ends with 1 without a word says the keyring may be locked,
  or the item already gone (ADR-0016).
- **`SecretFileStore`** writes each version to a new file created 0600 with `O_EXCL`
  (`Files.createFile` with the permission attribute), then renames it into place, in
  a directory created 0700, or tightened to 0700 unless it is a link, under a lock file. It refuses a file that others may read,
  that is not the user's, or that is a link, and never quotes the file in an error.
- **WebDAV and hub stores are closed** after each sync and status, as each holds an
  HTTP client.
- **Adding the PC hub** (`addPeer`, ADR-0015) refuses an IPv4 address outside loopback
  and `100.64.0.0/10` before anything is sent; the client checks a name each time it
  resolves it. The hub must answer `Reachable` with the token: a refused token, a hub
  whose folder is not set up, or one that cannot be reached is `Refused` in plain words,
  and this device never sets the hub up. The token is a `PAIRING_SECRET` in the
  `SecretStore`. A refused token later reads as "unpaired on the PC"; `changePassword`
  takes the new token after pairing again.
- **A `TailnetCheck`**, given to `BackendManager` and `StandardBackendFactory`, can keep
  a token in even where the address rule allows it: Android refuses while Tailscale's
  VPN does not carry the app, as mobile carriers use `100.64.0.0/10` too
  ([ADR-0018](../../docs/adr/0018-android-app.md)). The hub is then `OffTailnet`:
  unreachable with the reason, at adding, syncing and status, and sent nothing. Its
  `engine` may give the hub client an HTTP engine of the app's: Android's binds its sockets
  to Tailscale's network. The desktop and the CLI pass `TailnetCheck.NONE`, the default.

## Design

- **Adding a local folder** never sets up a folder by itself
  ([ADR-0012](../../docs/adr/0012-local-folder-publishing.md)). A folder that is a
  Jotter store already is added. One that is not yet answers `NotAStoreYet`, and only
  a second call with `setUpNewStore`, after the user said yes, runs `initialize()`.
  The same folder is not added twice. Paths must be absolute and are normalised. A
  folder is refused if it holds the notes database or is in the database's folder,
  if it is inside or holds a backend folder of this device, or if it is inside a
  folder with a `.jotter-store` marker: stores must not nest. Paths are compared after
  resolving symbolic links.
- **Removing** takes the backend off the list first, then `SyncEngine.forget`, then
  its secrets. Its files stay. If the process dies in between, what is left are sync
  records under an id that is never used again: a new backend always gets a new id.
- **One operation at a time.** `BackendManager` runs adding, removing and each sync
  under one mutex, so a removal waits for a running sync, and a sync re-reads its
  backend and skips one that is gone. Across processes (the CLI while the app runs)
  `SyncEngine` keeps the sync tables consistent on its own; a sync running in the app
  while the CLI removes that backend can still leave sync records for the removed id,
  as can a process that dies while removing. So `syncAll` first calls
  `SyncEngine.forgetUnlisted`, which forgets, in one transaction, every backend with
  sync records and no row in the `backend` table. A backend that another process adds
  meanwhile has its row before it has any records, so it is never taken for one.
- **Outcomes.** A sync never throws, except for cancellation: a backend's failure, a
  database error or a secret store that fails end as an `Outcome` with a plain
  description, recorded in the `backend` table with the time. The last success is kept
  apart, so a backend that fails now still shows when it last synced. Issues from
  `SyncReport` (damaged, missing, unreadable or newer files, too large changes, clock
  drift) are stored as plain sentences. Only `SYNCED` counts as a success and moves the
  last success on. A cycle that ran to its end while some listed files could not be read
  (`UNREADABLE`) is `INCOMPLETE`: changes may be missing, so it is retried like a
  failure. Damaged, missing and newer files stay reported on every cycle
  ([ADR-0014](../../docs/adr/0014-sync-failure-handling.md)) and a retry cannot change
  them, so they do not hold the success back.
- **Never empty, never synced by mistake.** `describe(Reachability)` never says or
  implies that a store is empty, and status always pings the backend afresh, so a
  missing folder or an empty mount point shows as such, beside the time of the last
  success.
- **Scheduling** (PLAN.md §6.3): one round on start, once the revision mark is known;
  one 5 s after edits stop (the mark rises); every 5 minutes; and on demand. A round
  syncs every backend in turn, so there is one sync of the process at a time. Requests
  that queue up during a round are served by one more round. Revisions that a round
  brought in raise the mark, which causes one more round: that sends on merges, and
  relays to the other backends. A backend whose sync failed is left out for 30 s,
  doubling up to 30 minutes, except on demand; a local folder that cannot be reached
  is tried every 30 s instead, as that is only a look at the disk. When its wait is
  over, a failed backend is tried on its own at once, so a USB stick syncs within
  30 s of being plugged in, and a backend that is back syncs without waiting for the
  next round. Cancelling the scheduler's job stops a sync where it is, which
  `SyncEngine` allows. Nothing else stops the scheduler: a round that throws, or a
  revision mark flow that fails (it is collected again after 30 s), shows in
  `SyncScheduler.problem` in plain words until it works again.
- **Background jobs.** `syncAndWait` asks for a scheduled round and returns once a
  round that started after the call has ended, so that a job such as Android's
  WorkManager worker (`app:android`) does not end before its sync. It goes through the
  same queue, so it never runs beside another round and honours the backoff above. A
  round already running does not count, and stopping the scheduler cancels the wait.
- **Coroutines.** Database calls run on the `io` context given; `SyncEngine` and each
  backend use their own dispatchers. Nothing blocks the caller's thread.

## Tests

`TailnetCheckTest` (JVM) shows that a refusing check sends a hub nothing, at adding and
syncing. `HubForAndroidTest`, in the package `e2e`, is the PC's half of the Android app's
end-to-end test and runs only when `app/android/scripts/hub-e2e.sh` sets
`JOTTER_HUB_E2E_DIR`. `PeerBackendTest` (JVM) syncs two devices through a real hub on 127.0.0.1, unpairs one,
pairs it again, and checks the refusals. `WebDavBackendTest` adds, changes and removes WebDAV backends against a stand-in
server. `SecretServiceStoreTest` uses a stand-in `secret-tool` that logs its arguments
and bus, to show that the secret is never in either, and runs the real `secret-tool`
against a private `dbus-daemon` the test starts, with no keyring on it, to show it
fails closed (no keyring daemon is installed here, so storing in a real keyring is not
tested). `SecretFileStoreTest` checks the modes, links, damage and the XDG paths.
`BackendManagerTest` uses real databases and real folders in a temporary directory:
two devices converge through a folder, an unplugged folder and an empty mount point
read as unreachable and are never set up, removing leaves the files, and failures of a
backend, of the database and of a secret store end as outcomes. `SyncSchedulerTest`
runs the scheduler on virtual time against a stand-in for the backends. They run on
the JVM and on `linuxX64`.
