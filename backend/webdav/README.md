# backend:webdav

A `BlobStore` in a collection on a WebDAV server, such as mailbox.org's, a
Nextcloud or a NAS, over the Ktor client
([PLAN.md §3.4](../../docs/PLAN.md#34-key-technology-choices)). What it relies on,
and which servers it was tested with, is
[ADR-0017](../../docs/adr/0017-webdav-backend.md).

```kotlin
val store = WebDavBlobStore(
    "https://dav.example.org/files/me/jotter/",
    WebDavCredentials("me@example.org", appPassword),
)
store.initialize() // once, when the user adds the backend
store.close()      // when done: it holds an HTTP client
```

**Dependency rule:** depends on `backend:api`, Ktor's client with the CIO engine,
and coroutines. No other Jotter module.

## What is on the server

```
<collection>/
├── .jotter-store          # marker, from initialize(); empty
├── .jotter-probe-*        # only while initialize() runs, or after it failed
└── jotter/...             # the files, exactly as passed to writeNew
```

`list` skips every name starting with `.jotter-`, like any name that cannot be a
path. Deleting `.jotter-store` makes the store unreachable until it is initialised
again.

## Behaviour

- **`https` only**, or `http` to this machine (`localhost`, `127.0.0.0/8`,
  `[::1]`), which the tests use. Anything else would send the password and every
  note in the clear, so the constructor refuses it. A URL with a user name or
  password in it is refused too: they go in `WebDavCredentials`, whose
  `toString` hides the password. Nothing in this module logs.
- **`initialize()` is for the "add backend" action only.** It needs the collection
  to exist, and checks that the server keeps files: it writes a test file twice
  with `If-None-Match: *`, and refuses the server unless the second write fails
  with 412. Then it writes the marker. Calling it because `ping` said
  `NOT_INITIALIZED` would make any collection a store.
- **`ping`** asks for the marker with `PROPFIND`: `Reachable` if it is there,
  `NOT_INITIALIZED` for a collection without it, `MISSING` if the collection is
  gone, `UNAUTHORIZED` for 401 and `FAILED` for anything else, such as no
  connection, a timeout, a 5xx or a redirect to another server.
- **`writeNew`** is `PUT` with `If-None-Match: *`: 412 means the path is taken.
  If the `PUT` fails in a way a missing parent explains (409, 404, or lighttpd's
  500), it creates the collections with `MKCOL`, never the store's own, and tries
  once more. A collection made by another writer meanwhile is fine. Any other
  refusal is told apart with a `PROPFIND`: something at the path gives
  `BlobAlreadyExistsException`, as servers answer a `PUT` onto a collection with
  405, 409 or 412.
- **A retried `PUT` that finds the file** throws `BlobAlreadyExistsException`,
  as the `BlobStore` KDoc asks: the first attempt may have gone through with its
  answer lost, and the caller compares the content.
- **Two writers racing for one path** both win on a server whose
  `If-None-Match` is not atomic, such as Apache's `mod_dav`. Nothing a client can
  send prevents that (ADR-0017). Sync does not depend on it: a path has one
  possible content (ADR-0014).
- **`list`** is `PROPFIND` with `Depth: 1` per collection, never `infinity`. It
  returns all or fails: a member whose properties failed, a file without a length,
  an `href` outside the collection, a name listed twice, a collection that goes
  away during the walk, or an answer over 32 MiB fails the call. A 404 for the
  prefix means empty only after the marker is found.
- **`read`** streams the body into memory, and fails rather than hold more than
  32 MiB, the limit of a sync file (ADR-0014). A body shorter than its
  `Content-Length` is retried. `writeNew` refuses more than 32 MiB too. A `GET`
  of a collection is "not found", whether the server says 404, 405 or redirects.
- **Retries.** A request is sent at most 3 times: again after no answer or a
  broken connection, and after 429, 502, 503 or 504, waiting 0.5 s and then 1 s,
  or the `Retry-After` the server asks for, up to 30 s. A longer wait fails at
  once. After that a connection failure or 503 is `BackendUnavailableException`.
- **Timeouts.** 20 s to connect, 60 s without a byte sent or received, 10 minutes
  for a whole request (a 32 MiB file at 1 Mbit/s takes 4.5 minutes). CIO ignores
  its own socket timeout on linuxX64, where a stalled server waited out the whole
  request timeout, so `IdleWatch` enforces the 60 s on every target: the body is
  written in chunks and the answer read in chunks, and a watchdog on real time
  cancels the request when neither moves.
- **Redirects** are followed, at most 5, keeping the method and body, but only
  within the store's scheme, host and port, so the password never goes elsewhere.
- **401** is `BackendUnavailableException` with `UNAUTHORIZED` from every method:
  the store is not usable until the user changes the password. 507 says the
  server is full.
- **Names** are encoded per segment as UTF-8, everything but `A-Z a-z 0-9 - . _ ~`
  escaped, and `href`s are decoded with escapes in either case, raw non-ASCII
  letters, full URLs or absolute paths. Unicode is not normalised: a server that
  stores names in another normal form returns other paths.

## Design

- **`DavClient`** sends requests: authentication, retries, redirects, timeouts and
  the read limit. **`DavServer`** makes the requests of one collection and says
  what a status means for the store. **`WebDavBlobStore`** holds the store's
  rules. **`DavLocation`** builds URLs and decodes `href`s. **`Xml`**,
  **`Multistatus`** and **`DavListing`** read `PROPFIND` answers.
- **A small XML parser of our own** rather than a library: the answers are small
  and simple, and it refuses document type declarations, so no entity can expand
  or reach outside the text.
- **CIO on every target**, as it needs no native library (libcurl) on linuxX64.
  The internal constructor takes any `HttpClientEngine`, which is how the tests
  use Ktor's `MockEngine`.
- **`close()` closes the engine too**, which a Ktor client does not do for an
  engine it was given. On linuxX64 each open CIO engine keeps a thread of
  `Dispatchers.IO` polling, and with 64 of them left open the tests hung.

## Tests

In `check`, on the JVM (counted by the coverage gate), linuxX64 and the Android
host, and on Android devices (`connectedAndroidDeviceTest`, in CI on API 26 and
36), where the loopback servers run inside the test app. The live tests below do
not run on a device:

- `FakeServerContractTest` runs the contract suite against `FakeDavServer`, a
  WebDAV server in memory inside Ktor's `MockEngine`.
- `WebDavSetupTest`, `WebDavListTest` and `WebDavReadWriteTest` cover each answer
  the servers below give differently, injected faults (refused and dropped
  connections, 5xx, `Retry-After`, a short body, redirects, 507, 401), and the
  limits.
- `SocketFaultTest` puts the real CIO engine against a TCP server that refuses,
  hangs or cuts an answer short.
- `DavLocationTest`, `XmlTest` and `MultistatusTest` cover the parts.

Against real servers, by `scripts/live-test.sh`, which CI runs in its `webdav` job
(the package `live`, skipped by `check`):

| Server | Image | How it differs |
|---|---|---|
| lighttpd 1.4.79, `mod_webdav` | `debian:trixie-slim` + Debian snapshot | `If-None-Match` atomic. 301 for a `GET` of a collection. Serves a file for a path below it (path info) unless `static-file.disable-pathinfo` is on, as in our config. 1.4.85 on Alpine also answered 500 for a `PUT` whose parent is missing |
| Apache httpd 2.4.69, `mod_dav_fs` | `httpd:2.4` | `If-None-Match` checked, but not atomic: concurrent writers can all win. 400 for a `PUT` or `PROPFIND` below a file; lower-case escapes in `href`s; `DAV:` properties under a second prefix |
| rclone 1.75.2, `serve webdav` | `rclone/rclone` | Ignores `If-None-Match` and replaces files, so `initialize` refuses it |

`LighttpdContractTest` and `ApacheContractTest` run the contract suite;
Apache is excluded from `concurrentWritersOfOnePathHaveExactlyOneWinner`, which it
fails. `RcloneTest` checks the refusal. `LiveFaultTest` puts a TCP proxy between
the store and lighttpd, which drops uploads and downloads halfway, loses the
answer to a `PUT` that went through, answers 503 or hangs.

```sh
backend/webdav/scripts/live-test.sh            # jvm and linuxX64
backend/webdav/scripts/live-test.sh jvm        # one target
```

It needs Docker, curl and python3, starts each server on 127.0.0.1 at a free
port, and removes its containers by name when it ends. Apache and rclone are their
official images, pinned by digest. lighttpd has no official image: the script
installs `lighttpd=1.4.79-2` into a pinned `debian:trixie-slim` from
`snapshot.debian.org` at a fixed date, which keeps every build for good, where
Alpine's repositories drop a superseded one and broke the earlier pin. That is still a
download when the container starts. A pinned third-party lighttpd image would avoid it,
but running one was refused by this machine's permission rules, so none was tried. Their logs are kept in
`build/live-test/`.

mailbox.org itself has not been tested: see "Not verified" in ADR-0017.
