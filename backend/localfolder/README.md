# backend:localfolder

A `BlobStore` over a directory: a USB stick, or a folder that another tool
replicates. How it writes, and how it tells a store from an empty mount point, is
[ADR-0012](../../docs/adr/0012-local-folder-publishing.md).

```kotlin
val store = LocalFolderBlobStore("/run/media/me/STICK/jotter-sync".toPath())
store.initialize() // once, when the user adds the backend
```

**Dependency rule:** depends on `backend:api`, okio, atomicfu and coroutines. No
other Jotter module.

## What is on disk

```
<root>/
├── .jotter-store            # marker, from initialize(); empty
├── .jotter-lock             # lock file for publishing; empty
├── jotter/...               # the files, exactly as passed to writeNew
└── jotter/.../.jotter-tmp-* # only while writing, or after a crash
```

`list` skips every name starting with `.jotter-`, at any depth, and those names
are not valid paths, so a temporary file cannot be read or listed. It is safe to
delete a temporary file when no Jotter process is running. Deleting `.jotter-store`
makes the store unreachable until it is initialised again.

## Behaviour

- **`initialize()` is for the "add backend" action only.** Calling it because
  `ping` said `NOT_INITIALIZED` would turn the mount point of an unplugged stick
  into a store. It needs the folder to exist, and does not create it.
- **`ping`** returns `Reachable` if the marker is there, `MISSING` if the folder
  is gone, `NOT_INITIALIZED` for a folder without the marker and `FAILED` if the
  root cannot be examined. It looks at the root only. A read-only stick is
  reachable, and `writeNew` fails with `BlobStoreFailureException`.
- **Errors are never absence.** Only "no such file" counts as absent. Any other
  error from a lookup or a listing fails the call, and a store that goes away
  during a call raises `BackendUnavailableException`, never an empty or partial
  list or a missing file.
- **`writeNew` on FAT or exFAT** has the same guarantee as elsewhere for all
  processes on one machine, through a lock file instead of a hard link. ADR-0012
  lists exactly which setups keep it, and which do not, such as several machines
  on a share with local locks and no hard links. On the JVM, exFAT through FUSE
  (`fuseblk`) on a system that translates error messages fails every write: the
  message cannot be recognised, and the file store's type does not give it away.
- **Link errors.** Only "hard links are unsupported here" leads to the rename. Any
  other link error fails the write, as a rename could replace a file.
- **Locks.** Hard links work without the lock. Without hard links a lock failure
  fails every write, so on storage with neither the backend cannot write at all.
- **A failed directory `fsync` fails `writeNew`**, but the file stays in place. A
  retry that finds it syncs again, then throws `BlobAlreadyExistsException`, and
  the caller compares the content. That is no proof of durability, as Linux can
  report a failed writeback only once. Only "not supported" results are tolerated.
  See the KDoc of `BlobStore.writeNew`.
- **Names this process cannot express**, such as a non-ASCII name under `LC_ALL=C`
  on the JVM, are skipped by `list` like other invalid names. `writeNew` of such a
  path fails with `BlobStoreFailureException`, and may leave a new empty directory
  above it, which does not show. `read` fails the same way, or with
  `BlobNotFoundException` if the directory above it does not exist.
- **Android `content://` folders are not supported**, because the store needs a
  real file path. M5 must address them.
- **Case.** On FAT `a` and `A` are one file, so the second write fails with
  `BlobAlreadyExistsException`. The sync layout has no such pairs.
- **Symbolic links** inside the store are not followed: `list` and `read` act as
  if they were not there, and `writeNew` refuses to go through one. The root itself
  may be a link.
- **Foreign files** are listed like any other, unless their name is not a valid
  segment (such as one with a colon or backslash), which are skipped.

## Design

- **okio in common code**, with `expect` declarations in `Platform.kt` for what it
  lacks or gets wrong: strict lookup and listing, hard link, record lock, directory
  `fsync` and the I/O dispatcher. The JVM and Android share one implementation in
  `src/javaNioMain`, through a `javaNio` group in the source set hierarchy.
  `linuxX64Main` has the other. Native-only code is not measured by the coverage
  gate, so it is kept this thin.
- **Walks paths one segment at a time** instead of asking the system about the
  whole path. Platforms disagree on the error for a file above the path.
- **`NoReplaceWriter`** holds the steps of a write. `LocalFolderBlobStore` holds
  the store's rules. `FolderAccess` bundles the platform calls, and its internal
  constructor is how the tests disable hard links or locks and inject errors.

## Tests

`HardLinkContractTest`, `RenameContractTest` (`link` disabled, as on FAT) and
`NoLockContractTest` (no lock) run the whole contract suite on the JVM (counted by
the coverage gate), linuxX64 and the Android host. `LocalFolderBlobStoreTest`
covers what is specific to a folder: the marker, stale temporary files, failures
in each step through okio's `ForwardingFileSystem`, and symbolic links.
`LocalFolderFaultTest` covers errors that must not read as absence, retries and
lock failures, with real `chmod` errors where a test can cause them. Real `EIO`
from `readdir` and from `fsync` cannot be caused from a test. Those were checked
by hand with an `LD_PRELOAD` shim, on the JVM and linuxX64.

`TwoProcessTest` (JVM) and `NativeTwoProcessTest` (linuxX64) race three processes
for 200 paths in both modes. Without the lock file, the rename mode fails them, as
checked by removing it. `TwoProcessTest` also kills a writer with `SIGKILL` in the
middle of writing, then checks that every listed file is complete and that the
next write works.

These have not run on a real FAT or exFAT mount or on an Android device. See
"Not verified" in ADR-0012.
