# 0012. Publishing files in a local folder, and telling a store from a mount point

- **Status:** Proposed
- **Date:** 2026-11-02

## Context

`writeNew` must fail if the path exists, also when two writers race, in one
process or in two ([ADR-0004](0004-immutable-change-segments.md)). A reader must
never see a part of a file, and a crash must leave no file at the final path.
`backend/localfolder` has to do this on the JVM, Android and `linuxX64`
([ADR-0006](0006-native-compatible-shared-modules.md)), and on a USB stick, which
usually means FAT or exFAT.

The usual tools each fall short:

- Writing a temporary file and renaming it is atomic, but `rename` replaces the
  target on POSIX, so two writers both "succeed".
- Creating the final path with `O_EXCL` refuses a second writer, but the file is
  visible while it fills up, and a crash leaves half of it there.
- `link(temp, final)` is atomic and refuses an existing target. But FAT and exFAT
  have no hard links: Linux's vfat and exfat drivers define no `.link` operation.
  An Android app cannot make them either: an AOSP SELinux `neverallow` rule denies
  it. Since which Android version is unconfirmed; "Android 6" is from memory.
- `renameat2(RENAME_NOREPLACE)` would suit FAT on Linux too, but the JVM cannot
  reach it: JDK 21 has no such call in `java.nio`, and the foreign function API
  is still a preview there.
- A claim file made with `O_EXCL` needs no special support, but a crash leaves
  the claim behind and the path blocked. Breaking a stale claim by its age is
  itself a race, and depends on clocks and FAT's timestamps.

The second problem is `ping`. An empty directory can be a new store or the bare
mount point of an unplugged drive. Treating the second as the first would make
sync seed the whole history into the mount point, under where the drive will
appear again, and would make every other device's notes look gone.

## Decision

**Publishing.** `writeNew` does this, in order:

1. Create the directories above the file.
2. Write the content to `.jotter-tmp-<uuid>` in the file's own directory, and
   `fsync` it.
3. Try to take the exclusive POSIX record lock on `.jotter-lock` in the store's
   root. That is `FileChannel.lock()` on the JVM and Android, and `lockf` on
   Native. Both are `fcntl` locks over the whole file, so they exclude each other.
   A process-wide mutex comes first, because such a lock does not exclude threads
   of one process, and closing the file in any thread drops it.
4. Check that the store is still there, then try `link(temp, final)`. If the
   target exists, the write fails with `BlobAlreadyExistsException`.
5. If the link failed because hard links are unsupported here (`EPERM`, `EACCES`,
   `ENOTSUP`, `EOPNOTSUPP`, `ENOSYS`) and the lock is held, look up the target and
   `rename` the temporary file onto it only if the system says that nothing is
   there. Any other result of the lookup fails the write. If the lock is not held,
   the write fails: the check and the rename are never done without it.
6. Any other link error, such as `EIO` or `ENOSPC`, fails the write. It says
   nothing about hard links, and a rename here could replace another writer's file.
7. Release the lock, delete the temporary name if it is still there, and `fsync`
   the directories up to the root.

On the JVM `EACCES` has an exception type, but the others arrive as a
`FileSystemException` with only text. Exactly three texts count, in English, and
so does any error on a file store of type `vfat`, `msdos` or `exfat`, which covers
a translated system. Any other text, such as a translated `EPERM` on another file
system without hard links, or exFAT through FUSE (`fuseblk`) on such a system,
fails the write. That is loud, but it replaces nothing.

**Where the guarantee holds.** Exactly one writer wins a path if all the writers
of one folder, on one file system, fall under one of these:

- **Hard links work for every writer.** The file system's `link` decides, so this
  holds with or without locks, and across machines if a shared file system makes
  `link` atomic on its server, as NFS does. A link error then fails the write.
- **Hard links are unsupported for every writer, and all of them are on one
  machine.** The lock decides. A writer that cannot get it fails.
- **Hard links are unsupported for every writer, on several machines, and the
  shared file system passes `fcntl` locks between them.** NFS with a working lock
  manager or NFSv4 can.

It does **not** hold, and a file can be replaced, in these cases:

- Hard links are unsupported and locks are local to each machine, as on a mount
  with `nolock` or `nobrl`, or through FUSE: each machine's writers hold a lock of
  their own.
- Writers disagree on whether hard links work, for example through different
  security policies or mount options, and one that links does not hold the lock.
  It lacks the lock if taking it failed (`EMFILE`, `ENOLCK`, a `.jotter-lock` owned
  by another user and not writable) or if locks are local. The one that renames
  then cannot see it. A writer that links while holding the lock is safe.
- Folders replicated by a tool such as Syncthing or Dropbox are not one file
  system. Nothing here applies across the copies: ADR-0004's per-device folders are
  what keeps writers apart there.

**A lock failure does not block hard links.** A link never replaces anything, so
it needs no lock, and storage that has hard links but no working `fcntl` locks
stays usable. Only a rename needs the lock. So on storage with neither, every
write fails with an error, and the backend is unusable there; `ping`, `list` and
`read` still work. The price is the second case above. The alternative, failing
every write without the lock, would make hard-link storage unusable to protect
against writers that disagree about hard links, which needs an unusual setup.

The kernel drops the lock when a process dies, so a crash leaves no stale lock.
`.jotter-lock` itself stays, as an empty file.

**Errors are never absence.** okio's `metadataOrNull` returns null for any error
on the JVM and Android, and its `list` stops silently at a read error. So every
lookup goes through `strictStat`, where only `ENOENT` (`NoSuchFileException`) means
nothing is there, and every listing through `strictListNames`, which fails on any
error, including one in the middle of a directory. Before `list` returns and
before `read` says a file is missing, the store checks again that it is reachable,
so one that went away during the call raises `BackendUnavailableException`. `ping`
looks only at the root: an unreadable directory further down leaves it `Reachable`,
and `list` and `read` below it fail with `BlobStoreFailureException`.

**A failed directory sync fails the write.** `fsync` on a directory is tolerated
only if the file system says it has no such operation (`EINVAL`, `ENOTSUP`,
`EOPNOTSUPP`, `ENOSYS`, or the JDK's text for those). Any other error makes
`writeNew` fail after the file has appeared. The file stays: it is complete and
immutable, and a reader may have seen it. The retry is defined in the KDoc of
`BlobStore.writeNew`. A retry that finds the file syncs the same directories again
before it throws `BlobAlreadyExistsException`. If that sync fails, so does the
retry. If it reports no error, that is no proof: Linux can report a failed
writeback once and then let `fsync` return 0, so the entry may still not be on
disk. So a retry cannot honestly report success for a file it found, and it does
not: it cannot tell whose file that is, and it knows no more about durability than
the failed call did. The caller gets `BlobAlreadyExistsException`, compares the
content (ADR-0004), and must allow for a segment that was on the backend and is
lost by a later power cut. Segment numbers are sequential, so such a hole shows; M3's
sync has to deal with it. The JDK gives the error only as text, which can be
translated. A translated "unsupported" then fails loudly, which is the safe way
round.

**Stale temporary files.** They have random names and are never read, and `list`
skips every name starting with `.jotter-`, at any depth. Nothing removes them: a
store cannot tell a dead writer's file from a live one's without depending on
clocks, and one crash costs one hidden file. `.jotter-` is reserved in
`backend/api`, so no valid path can reach these names on any backend.

**Marker.** `LocalFolderBlobStore.initialize()`, called once when the user adds
the folder, creates an empty `.jotter-store` in the root. It requires that the
root exists. Without the marker the store is unreachable: `ping` answers
`NOT_INITIALIZED`, or `MISSING` if the root is gone, and `list`, `read` and
`writeNew` throw `BackendUnavailableException`. Sync must never call
`initialize()` by itself. `initialize()` is not part of `BlobStore`, as only
this backend needs it, so the code that adds a backend calls it on the concrete
class.

**File I/O.** okio for everything it can do, in common code. Its
`FileHandle.flush()` is `fsync`. What it lacks or gets wrong (strict lookup and
listing, hard link, record lock, directory `fsync`, the I/O dispatcher) are
`expect` declarations with one `actual` in a `javaNio` source set shared by the
JVM and Android, and one in `linuxX64Main`. Android's minimum API 26 has
`java.nio.file`.

**File paths only.** The store takes an okio `Path`. Android's Storage Access
Framework hands out `content://` folders, which have no path, and `fcntl`, `link`
and a strict `stat` have no equivalent there. So those folders are out of scope.
M5 must decide how Android users reach a USB stick or shared folder: a separate
`BlobStore`, or a different folder picker.

## Consequences

### Positive

- `writeNew` keeps its promise on hard-link and FAT file systems with one code
  path. The contract suite runs against both ways of publishing on every target,
  by replacing `link` with one that always fails.
- A crash needs no recovery: no stale lock, and no partial file at a final path.
- An unplugged stick reads as unreachable, whether its mount point disappears or
  stays as an empty directory.

### Negative

- Without hard links the guarantee is only as wide as `fcntl` locks are. That
  covers all processes on one machine, which is the USB stick case. "Where the
  guarantee holds" lists the setups that lose it.
- On storage with neither hard links nor working `fcntl` locks every write fails,
  so the backend cannot be used there.
- Android `content://` folders are not supported.
- A write can fail although its file is now there, so callers must follow the
  retry in the KDoc of `BlobStore.writeNew`.
- A crash on FAT between the two halves of a rename can leave both names. The
  temporary one stays hidden, and `fsck` may complain about the shared clusters.
- Every write opens and locks one more file, which waits for any other writer's
  publish step. If a process is stopped (not killed) inside that step, others
  wait until it resumes. Writes are rare and the step is short.
- A folder without the marker needs `initialize()` even if it holds a complete
  Jotter layout, for example if the marker was not copied along.
- A user who deletes `.jotter-store` makes the backend unreachable until it is
  added again.
- Stale temporary files stay until someone removes them by hand.

### Not verified

The fallback was tested by disabling `link`, on the file system of the temporary
directory. It has not run on a real FAT or exFAT mount, or on an Android device.
`testAndroidHostTest` runs on a host JVM, so `strictStat` and `strictListNames`
have not run on Android's own `java.nio`, and whether `FileChannel.force` works on
a directory there is unknown.

That `fcntl` locks work on Android, on app storage or the storage FUSE layer, is
unconfirmed. If they do not, the fallback fails with an error on Android, which
has no hard links, so the backend would be unusable there until M5 deals with it.
It would not break the guarantee. The two processes in the tests are both JVM or
both Native; that the two exclude each other follows from both being `fcntl` locks
over the same range.

Read and directory `fsync` errors cannot be injected from a Kotlin test. The tests
replace those calls, and real `EACCES` comes from `chmod`. Real `EIO` from `readdir`
and from `fsync` on a directory was checked once by hand on the JVM and `linuxX64`,
with an `LD_PRELOAD` shim. That is not part of `check`.

## Alternatives considered

- **A claim file per path, broken by age:** works without OS locks, but see
  Context. Stale claims block a segment number for good, or depend on clocks.
- **A commit marker or footer per file, checked by readers:** survives anything,
  but files would no longer be what the writer passed in, and a crash would leave
  something at the final path.
- **`renameat2` on Native only, or through a JNI library:** the first leaves the
  JVM, which is what ships, without it. The second adds a native library to every
  build, against the F-Droid and packaging goals.
- **Probing with a write in `ping`, or comparing device ids of the folder and its
  parent:** a write would land on the mount point, and the mount check would call
  every folder on the root file system fine, and has no equivalent on Android.
- **Creating the marker on first write:** the first write would then happily mark
  a bare mount point.
- **Requiring `jotter/format.json` instead of a marker:** the store would need to
  know the layout it is meant not to interpret, and sync writes that file exactly
  when it thinks the backend is new.
