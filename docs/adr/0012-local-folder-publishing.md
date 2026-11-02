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
  have no hard links, and neither, as far as we know, does an Android app: its
  SELinux policy has denied `link` since Android 6.
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
3. Take the exclusive POSIX record lock on `.jotter-lock` in the store's root.
   That is `FileChannel.lock()` on the JVM and Android, and `lockf` on Native.
   Both are `fcntl` locks over the whole file, so they exclude each other. A
   process-wide mutex comes first, because such a lock does not exclude threads
   of one process, and closing the file in any thread drops it.
4. Check that the store is still there, then try `link(temp, final)`. If the
   target exists, the write fails with `BlobAlreadyExistsException`.
5. If the link failed in any other way, check that the target does not exist and
   `rename` the temporary file onto it, still under the lock.
6. Release the lock, delete the temporary name if it is still there, and `fsync`
   the directories up to the root, as far as the platform allows.

With hard links the lock adds nothing for the race, and the guarantee holds even
for writers on several machines. Without them (FAT, exFAT, Android) the check and
the rename in step 5 are safe because every writer holds the lock.

Any link failure leads to step 5, because the JDK reports `EPERM` and the like
with no error code to look at. A real I/O error then hits the rename as well.
`writeNew` takes the lock on both paths, so that writers for which `link` gives
different answers on one folder still cannot overwrite each other.

The kernel drops the lock when a process dies, so a crash leaves no stale lock.
`.jotter-lock` itself stays, as an empty file.

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
`FileHandle.flush()` is `fsync`. The four things it lacks (hard link, record
lock, directory `fsync`, the I/O dispatcher) are `expect` declarations with one
`actual` in a `javaNio` source set shared by the JVM and Android, and one in
`linuxX64Main`. Android's minimum API 26 has `java.nio.file`.

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
  covers all processes on one machine, which is the USB stick case. Two machines
  on a network share without hard links would need the share to pass locks on,
  and a share mounted with local locks only would not.
- The check in step 5 depends on `metadataOrNull`, which okio on the JVM and
  Android makes return null when a lookup fails. A failed lookup with a working
  rename would then replace the file. We accept this for such a double fault.
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
`testAndroidHostTest` runs on a host JVM. That FAT has no hard links, and that
Android denies them, comes from documentation and memory, but neither matters for
correctness: if `link` works there, step 4 is used. That `fcntl` locks work on
Android's storage FUSE layer is an assumption. If they do not, `writeNew` fails
with an error instead of breaking the guarantee. The two processes in the tests
are both JVM or both Native; that the two exclude each other follows from both
being `fcntl` locks over the same range.

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
