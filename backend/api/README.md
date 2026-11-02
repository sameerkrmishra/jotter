# backend:api

The `BlobStore` interface that every sync backend implements
([ADR-0004](../../docs/adr/0004-immutable-change-segments.md)): a dumb file store
with four methods. The code above it never sees what kind of store it talks to.

```kotlin
store.writeNew("jotter/format.json", bytes) // BlobAlreadyExistsException if taken
store.read("jotter/format.json")            // BlobNotFoundException if missing
store.list("jotter/devices")                // every file below it, sorted
store.ping()                                // Reachable, or Unreachable with a reason
```

**Dependency rule:** depends on nothing, no other Jotter module and no library.

## The rules

The KDoc of `BlobStore` is the definition. The parts that are easy to get wrong:

- **`list` takes a path prefix, not a string prefix.** `list("a/b")` finds `a/b/c`
  and `a/b/d/e`, but not `a/bc`, and not a file at `a/b`. This is what a WebDAV
  `PROPFIND` of a collection gives, and a directory has no partial names. The
  result is recursive, sorted by `path` in `String` order, and holds files only.
  A prefix with nothing below it gives an empty list.
- **An unreachable store never looks empty.** `list`, `read` and `writeNew` throw
  `BackendUnavailableException`, and `ping` returns `Unreachable`. Only `ping`
  never throws for this. An empty list always means no files.
- **Paths are checked first.** `requireValidBlobPath` rejects a path with an empty
  segment (and so a leading, trailing or double `/`), a `.` or `..` segment, a
  backslash, a NUL or a colon. It also rejects segments starting with `.jotter-`,
  ignoring case, which backends may use for their own files. Paths are read from
  remote stores, so a backend calls this first in every method. `list` allows the
  empty prefix through `requireValidBlobPrefix`.
- **All failures are `BlobStoreException`s**, a sealed class. So the sync engine
  can tell "the segment number is taken" from "skip this backend for now" from
  "the disk is full" without knowing the backend.

## Design

- No I/O types in the API: `ByteArray` and `String`, no okio, so a backend such as
  WebDAV does not inherit a file library.
- `BlobInfo` has only `path` and `size`. Sync finds new segments by their numbers,
  so it needs no timestamps, which many stores would not report faithfully.

## Tests

`commonTest` holds the path rules and runs on the JVM (counted by the coverage
gate), linuxX64 and the Android host. What a store must do is tested by the suite
in [`contract`](contract/README.md).
