# app:sync

What the apps share to sync: the list of backends and how each is opened, adding and
removing them, running and recording a sync, the status in plain words, and when the
desktop app syncs ([PLAN.md](../../docs/PLAN.md) §6.3 and §6.5). The desktop app uses
it through `app:shared-ui`, the CLI directly. It is a `jotter.kmp-library` module, so
it builds and tests on `linuxX64` too: the CLI needs it, and ADR-0010 keeps such logic
out of `app:shared-ui`.

```kotlin
val engine = SyncEngine(store, DeviceInfo("Linux", "linux"), Dispatchers.IO)
val manager = BackendManager(store, engine, StandardBackendFactory, NoSecretStore, Dispatchers.IO)
when (val result = manager.addLocalFolder("/media/stick")) {
    is AddResult.NotAStoreYet -> // ask the user, then:
        manager.addLocalFolder("/media/stick", setUpNewStore = true)
    else -> Unit
}
manager.syncAll()                       // one cycle per backend, recorded in the database
SyncScheduler(manager::syncAll).start(scope, store.notes.observeRevisionMark())
```

**Dependency rule:** `core:storage`, `core:sync`, `backend:api` (all in its API) and
each backend implementation it can open, today `backend:localfolder`. No UI.

## API

| Type | What it is |
|---|---|
| `BackendConfig`, `LocalFolderConfig`, `UnknownConfig` | Where a backend is and what secrets it needs. Stored as `kind` and JSON `settings` in the `backend` table, never with a secret. `UnknownConfig` is a kind this version cannot read, listed so that it can be removed, never synced |
| `Backend`, `Attempt`, `Outcome`, `BackendSync`, `BackendStatus` | A configured backend, how its last sync went, what one sync did, and its reachability now |
| `BackendFactory`, `StandardBackendFactory` | Opens the `BlobStore` of a backend |
| `BackendManager`, `AddResult` | Adds a local folder, removes a backend, syncs one or all, and reports status |
| `SyncScheduler`, `SyncTiming` | When the desktop app syncs |
| `SecretStore`, `Secret`, `SecretKey`, `SecretKind`, `NoSecretStore`, `SecretStoreException` | Where backend secrets live ([ADR-0016](../../docs/adr/0016-where-secrets-live.md)) |
| `describe(Reachability)` | Reachability in plain words |

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
- **Coroutines.** Database calls run on the `io` context given; `SyncEngine` and each
  backend use their own dispatchers. Nothing blocks the caller's thread.

## Tests

`BackendManagerTest` uses real databases and real folders in a temporary directory:
two devices converge through a folder, an unplugged folder and an empty mount point
read as unreachable and are never set up, removing leaves the files, and failures of a
backend, of the database and of a secret store end as outcomes. `SyncSchedulerTest`
runs the scheduler on virtual time against a stand-in for the backends. They run on
the JVM and on `linuxX64`.
