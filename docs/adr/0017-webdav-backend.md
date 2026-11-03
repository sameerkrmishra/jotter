# 0017. The WebDAV backend: what it asks of a server, and what no client can enforce

- **Status:** Proposed
- **Date:** 2026-11-03

## Context

M4 syncs through a WebDAV server, first of all mailbox.org's
([PLAN.md §10](../PLAN.md#10-milestones)). `BlobStore.writeNew` must fail if the
path exists, and exactly one of several racing writers may win
([ADR-0004](0004-immutable-change-segments.md)). PLAN.md §6.1 says WebDAV does this
with `PUT` plus `If-None-Match: *`. `list` must never return a part of the files,
and an unreachable store must never look empty. Files are at most 32 MiB
([ADR-0014](0014-sync-failure-handling.md)). The code must build and test on
`linuxX64` ([ADR-0006](0006-native-compatible-shared-modules.md)).

Before writing the client we measured three servers in containers, eight writers
racing for one path a hundred times each:

- **lighttpd 1.4.85** (`mod_webdav`): exactly one `201` and seven `412` in every
  round. It is single-threaded and handles a whole request at once.
- **Apache httpd 2.4.69** (`mod_dav_fs`, event or prefork MPM): a second `PUT`
  after the first gives `412`, but in a race several writers get `201`, with 100 KB
  bodies in every round. It checks, then writes a temporary file and renames it
  over the target. Its `LOCK` is not exclusive under a race either: 19 of 100
  rounds gave two or more locks on one file.
- **rclone 1.75.2** (`serve webdav`): ignores `If-None-Match` and replaces the file.
  `MKCOL` of an existing collection answers `201`. Its `LOCK` is exclusive, and
  concurrent `PUT`s to one path get `423`.

So no single WebDAV request is atomic on all three: `PUT` with `If-None-Match` is
atomic on lighttpd only, `LOCK` on rclone but not Apache (lighttpd's was not
measured), and `MKCOL` is not exclusive on rclone. A client can only be as
exclusive as the server.

## Decision

**`writeNew` is `PUT` with `If-None-Match: *`, and nothing more.** 412 is
`BlobAlreadyExistsException`. Retried `PUT`s that find the file report it as
there, as `BlobStore.writeNew`'s KDoc asks, and the caller compares. Exactly one
winner among racing writers holds where the server makes `If-None-Match` atomic,
and is not promised elsewhere. Sync does not need it, because since ADR-0014 a path
has one possible content: two writers who both win wrote the same bytes, and a file
that differs from its name's hash is reported, never applied. `device.json` is only
informative.

**The format file is named after its content (amends ADR-0004).** It was
`jotter/format.json`, whose content differs between versions: exactly what it exists
to catch, and on Apache two versions could both write it, the later replacing the
earlier. Now each format and codec has its own file,
`jotter/formats/<format>-<codec>.json`, holding `{"format":…,"codec":…}`. A device
lists `jotter/formats`. If another version's file is there, the backend is
`Incompatible`. If none is there, it writes its own and lists again, so of two
versions that set a backend up at once, at least the later one finds the other. If
its own is there, it reads it and treats any other content as damage. The earlier one
may push one cycle of segments first, which the later version's readers leave unread
as a future or past format (ADR-0014). Nothing had shipped, so nothing migrates.

**`initialize()` refuses a server that ignores `If-None-Match`.** It writes a test
file twice and needs 412 the second time. Such a server would let any retry
replace a file, which is a sequential failure, not a race. It is checked once,
when the backend is added, not on every write. A server that changes later is not
noticed.

**A marker, as for a local folder.** `initialize()` writes `.jotter-store` in the
collection, which must exist. `ping` is a `PROPFIND` of the marker, then of the
collection: `Reachable`, `NOT_INITIALIZED`, `MISSING`, `FAILED`, and a new
`UnreachableReason.UNAUTHORIZED` for 401, which every method also throws as
`BackendUnavailableException`, so the app can ask for a new password instead of
retrying. A 404 from `list` or `read` counts as absence only once the marker is
found. `writeNew` creates parent collections, never the store's own.

**`list` uses `PROPFIND` with `Depth: 1`, recursively, never `infinity`.** Every
server must support depth 1, and both lighttpd and Apache refuse `infinity` by
default. The layout is shallow, so this costs one request per device folder. It
returns all or fails: a member without a successful `resourcetype`, a file without
`getcontentlength`, a member's own error status, an `href` that is not a direct
member, a duplicate, a collection that disappears mid-walk or an answer over 32 MiB.

**Transport.** Ktor's CIO engine on every target. Basic authentication, sent with
every request; app passwords are recommended. `https` only, except `http` to a
loopback address. Same-origin redirects only, at most 5, keeping method and body.
Three attempts for a failed connection or 429/502/503/504, with backoff or the
server's `Retry-After` up to 30 s. Timeouts of 20 s to connect, 60 s between bytes
and 10 minutes per request. Bodies are read in chunks up to 32 MiB, and a
`Content-Length` that is not met is a failed connection.

**Tests against real servers run in a CI job of their own,** `webdav`, through
`backend/webdav/scripts/live-test.sh`, beside `check`. `check` runs the contract
suite and the fault tests against an in-memory server inside Ktor's `MockEngine`
on every target, and the CIO engine against misbehaving TCP servers.

## Consequences

### Positive

- One request per write, and nothing left behind by a crash: no claim files, no
  locks to expire.
- A server that would replace files is refused before any note goes to it.
- A lost answer, a dropped upload, a 5xx and a hanging server were each tested
  against a real server, at the TCP level.
- The UI can tell a wrong password from an outage.

### Negative

- **Two racing writers can both succeed on Apache**, and maybe on other servers
  that check and then rename. `ApacheContractTest` skips the one contract test that
  shows it. A future use of `writeNew` that writes different content to one path
  would not be safe there.
- An `If-None-Match` probe at setup proves the sequential case only, and only at
  that moment.
- `http` to a NAS on the local network is refused. A user without TLS there must
  add it, for example through a reverse proxy.
- Unicode names are not normalised. A server that stores names in another normal
  form, as some macOS-based ones do, gives other paths back. Sync's names are
  ASCII, so it is not affected.
- The `PROPFIND` walk is sequential: a folder of many devices takes one round
  trip each.

## Alternatives considered

- **A `LOCK` on a store-wide lock file around each write.** Exclusive on rclone,
  but not on Apache, as measured, so it buys nothing where it is needed.
  A crashed writer would also block every write until the lock expired.
- **A `MKCOL` claim per path, then the `PUT`.** Probably atomic on Apache, whose
  `MKCOL` is a `mkdir` (not measured under a race), but rclone answers `201` for an existing collection, and a crash between claim and
  `PUT` would block the path, a format file included, for good. Breaking stale
  claims by age is a race of its own.
- **Upload to a temporary name, then `MOVE` with `Overwrite: F`.** Apache's `MOVE`
  checks, then renames, so it races like its `PUT`; and a crash leaves temporary
  files.
- **Read back after each write.** Doubles the traffic, and still lets two writers
  each see their own content if they read before the other's rename.
- **Allow servers that ignore `If-None-Match`, with a `PROPFIND` before each
  `PUT`.** Correct when writers take turns, but a retry after a lost answer
  replaces the file silently, which is what `writeNew` exists to prevent.
- **`Depth: infinity`, falling back to depth 1.** Fewer requests, but often
  disabled, and one huge answer per store instead of one per folder.
- **libcurl (`ktor-client-curl`) on linuxX64** for TLS from the system. It needs
  libcurl at link time on every build machine, for a target that ships nothing yet.

## Verified, and how

`scripts/live-test.sh` runs the contract suite on lighttpd and Apache, the refusal
on rclone, and `LiveFaultTest` on lighttpd, on the JVM and linuxX64, over `http` on
127.0.0.1. The images are pinned by digest in the script. On 2026-11-03 every one
of these tests passed on both targets, 57 per target (Apache without its race
test). The race measurements
above came from a throwaway Python script with eight threads; the contract's own
race test, eight coroutines on 4 KB files for 30 rounds, failed on Apache in each
of the three JVM runs that included it.

## Not verified

- **mailbox.org has not been tested.** Its server, its `If-None-Match`, its answers
  to a `PUT` with a missing parent, its `href` style, its limits and whether it
  accepts Basic authentication with an app password are unknown to this work. The
  M4 exit criterion (desktop to mailbox.org to second desktop) is still open, and
  the first real run must start with `initialize()`, whose probe will tell whether
  it honours `If-None-Match` sequentially, though not whether it is atomic.
- **Nextcloud, ownCloud, NAS firmware and other servers** have not been tested.
- **TLS has not been tested at all**: every test is plain `http` to loopback. CIO's
  TLS on the JVM and Android is Ktor's own, and on linuxX64 it is unknown whether
  CIO can do TLS at all; the app ships no native build yet (ADR-0006).
- **Android** ran the in-memory tests on the host JVM only.
- **Proxies, captive portals and IPv6** were not tried.
