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
  is gone and `NOT_INITIALIZED` for a folder without the marker. A read-only stick
  is reachable, and `writeNew` fails with `BlobStoreFailureException`.
- **`writeNew` on FAT or exFAT** has the same guarantee as elsewhere for all
  processes on one machine, through a lock file instead of a hard link. Two
  machines on a network share without hard links are not covered. See ADR-0012.
- **Case.** On FAT `a` and `A` are one file, so the second write fails with
  `BlobAlreadyExistsException`. The sync layout has no such pairs.
- **Symbolic links** inside the store are not followed: `list` and `read` act as
  if they were not there, and `writeNew` refuses to go through one. The root itself
  may be a link.
- **Foreign files** are listed like any other, unless their name is not a valid
  segment (such as one with a colon or backslash), which are skipped.

## Design

- **okio in common code**, with four `expect` declarations in `Platform.kt`: hard
  link, record lock, directory `fsync` and the I/O dispatcher. The JVM and Android
  share one implementation in `src/javaNioMain`, through a `javaNio` group in the
  source set hierarchy. `linuxX64Main` has the other. Native-only code is not
  measured by the coverage gate, so it is kept this thin.
- **Walks paths one segment at a time** instead of asking the system about the
  whole path. Platforms disagree on the error for a file above the path, and okio
  would throw for one of them.
- **`NoReplaceWriter`** holds the steps of a write. `LocalFolderBlobStore` holds
  the store's rules. A hard link can be swapped out in its internal constructor,
  which is how the tests run the fallback for file systems without hard links.

## Tests

`HardLinkContractTest` and `RenameContractTest` run the whole contract suite, the
second with `link` disabled as on FAT, on the JVM (counted by the coverage gate),
linuxX64 and the Android host. `LocalFolderBlobStoreTest` covers what is specific
to a folder: the marker, stale temporary files, failures in each step through
okio's `ForwardingFileSystem`, and symbolic links.

`TwoProcessTest` (JVM) and `NativeTwoProcessTest` (linuxX64) race three processes
for 200 paths in both modes. Without the lock file, the rename mode fails them, as
checked by removing it. `TwoProcessTest` also kills a writer with `SIGKILL` in the
middle of writing, then checks that every listed file is complete and that the
next write works.

These have not run on a real FAT or exFAT mount or on an Android device. See
"Not verified" in ADR-0012.
