# 0015. The PC as hub: protocol, security, how it runs and where it stores

- **Status:** Proposed, amended by 0018
- **Date:** 2026-11-03

## Context

[PLAN.md §6.4](../PLAN.md#64-the-pc-as-hub) lets the Linux PC act as a backend for
the user's other devices: a **peer server** that exposes a `BlobStore` over HTTP,
reached over Tailscale. The phone adds it like any other backend
([ADR-0004](0004-immutable-change-segments.md)). Through the network, the hub must
keep every promise of `BlobStore`: `writeNew` never replaces, `list` is complete or
fails, and an unreachable store never looks empty
([ADR-0014](0014-sync-failure-handling.md)).

The hub is the first piece of Jotter that listens on a network. Anyone who can
reach its port could read every note the user has synced, or fill the PC's disk.
A tailnet is not a trust boundary on its own: it can hold shared nodes, other
users' devices, or a compromised machine. So security is the main risk, followed
by a bad client exhausting the PC that the user is working on.

Shared modules build for linuxX64 too
([ADR-0006](0006-native-compatible-shared-modules.md)), so the client must as
well, and must be tested on every target. The server runs only inside the desktop
app's JVM; whether it must follow the rule is decided below.

## Decision

### Transport and protocol

HTTP/1.1 over TCP, plain text, version 1. All of it is in
`backend/peer/PeerProtocol.kt`, which both sides use:

| Request | Success | Failure body |
|---|---|---|
| `GET /jotter-peer/v1/ping` | 200, `{}` | |
| `GET /jotter-peer/v1/list?prefix=P` | 200, JSON array of `{path, size}` | |
| `GET /jotter-peer/v1/blob?path=P` | 200, the bytes | |
| `PUT /jotter-peer/v1/blob?path=P` | 201 | |
| any failure | 400, 401, 404, 409, 411, 413, 500 or 503 | `{code, detail, reason?}` |

- **Versioned by path.** A later protocol is served under `/v2/`; a hub that does
  not know a request answers 400.
- **Paths go in the query string**, percent-encoded, never in the URL path, so
  that no HTTP layer can normalise `..` or `//` before the store's own checks.
- **Every answer carries `Jotter-Peer-Protocol: 1`.** The client refuses any answer
  without it as "not a hub", so a captive portal, a proxy or another server on the
  port cannot pass an empty listing off as an empty store.
- **One request per connection.** The hub always closes the connection after its
  answer. It costs a TCP handshake per file, which is cheap on loopback and a
  tailnet, and removes pipelining and keep-alive from the server.
- **Answers are read strictly.** The client requires `Content-Length`, reads
  exactly that many bytes, and treats a shorter body as a hub that went away
  (`BackendUnavailableException`). A listing must be sorted, without repeats, of
  valid paths below the prefix, with sizes that are not negative; anything else is
  `BlobStoreFailureException`. An error code counts only with its own HTTP status.
- **No retries and no redirects in the client.** A failed `writeNew` is retried
  by sync as the KDoc of `BlobStore.writeNew` says: a retry that finds the file
  gets 409 and compares.
- **`ping`** answers what the hub's own store answers (`Reachable`, or 503 with
  `MISSING`, `NOT_INITIALIZED` or `FAILED`). A hub that cannot be reached is
  `Unreachable(FAILED)`. A hub that refuses the token is
  `Unreachable(UNAUTHORIZED)`, a reason added to `backend:api` (as on the WebDAV
  branch, with the same name), so that sync stops retrying quickly and the user is
  told to pair the device again.
- **Paths must be UTF-8.** The hub decodes the query itself, strictly: invalid
  UTF-8 or a bad `%` escape is 400, never replacement characters.
- **Client timeouts.** 10 s to connect; 60 s for a whole `ping` or `list`; 15 min
  for a `read` or `writeNew`. Within those, a request that moves no bytes for 60 s
  fails. The client keeps that idle watch itself, around waiting for the answer's
  head and each part of a body in either direction, because Ktor's CIO engine does
  not apply its socket timeout on linuxX64.

### Authentication: per-device tokens

- **Pairing** happens on the PC: `PeerHub.pair(name)` returns a token, shown once.
  The user enters it on the device together with the hub's address. There is no
  pairing over the network, so there is nothing to attack before a device has a
  token.
- **A token is `<id>.<secret>`**: 32 hex digits naming the device, then 32 random
  bytes in unpadded base64url (244 bits of randomness, from two `Uuid.random()`
  calls, which Kotlin documents as a cryptographically secure source on every
  platform). It travels as `Authorization: Bearer <token>`.
- **The hub keeps only hashes**: the id, the name and the SHA-256 of the secret, in
  a JSON file it replaces atomically. Reading that file does not give a token. A
  secret this long needs no slow hash: it cannot be guessed, only stolen.
- **Checking is constant-time**: the hash of the presented secret is compared with
  every device's hash, without an early exit, by XOR over all bytes. The id is
  public and compared plainly.
- **The token is checked first**, before the request target is parsed, so an
  unauthenticated client cannot learn anything else, not even which requests
  exist.
- **Per device, not one shared secret**, so a lost phone is unpaired alone, at
  once, also on a running hub.
- **Secrets are never logged or echoed**: the hub logs nothing, no answer holds a
  token, `Pairing.toString()` leaves the token out, and the client puts it in no
  message. A damaged token file stops the hub from starting instead of being
  replaced, and is never read as "no devices".

### Network exposure and TLS

- **The hub listens on one IPv4 address**, given as a literal: loopback
  (`127.0.0.0/8`) by default, or the PC's Tailscale address (`100.64.0.0/10`).
  `HubConfig` refuses anything else: host names, `0.0.0.0` and LAN or public
  addresses. Binding to a specific address means the hub is not reachable on the
  LAN even when the PC is.
- **No TLS. WireGuard, inside Tailscale, encrypts and authenticates** every packet
  between the two machines, with keys that Tailscale manages per node. TLS on top
  would add a certificate the phone has to trust or pin, with no public name to
  issue it for, and protect nothing more on this path. Plain HTTP is only sound
  because of the address rule above: the hub cannot be bound where WireGuard does
  not wrap it.
- **The client sends its token only into the tailnet.** Before every request it
  resolves the hub's name, checks the address, and sends the request to that same
  address, so the address checked is the one connected to. Allowed: loopback
  (`127.0.0.0/8`, `::1`), `100.64.0.0/10` and Tailscale's IPv6 range
  `fd7a:115c:a1e0::/48`. Anything else, such as a LAN address or a name that
  resolves to one, is refused with a plain message, before a byte is sent. There is
  no opt-in. Only the first address a name resolves to is used.
- **IPv6.** The hub listens on IPv4 only; it cannot be bound to an IPv6 address,
  not even Tailscale's. The client accepts Tailscale's IPv6 addresses and `::1`,
  but a hub is reachable there only once a later change lets it listen on them.
- **An allowed address on another interface.** `100.64.0.0/10` is also the shared
  address space of carrier-grade NAT. A PC on a phone hotspot or behind some ISPs
  can hold such an address on a network that is not the tailnet. The address rule
  cannot tell: binding the hub to that address would serve plain HTTP on that
  network, with tokens in it. The user must give the address Tailscale shows for
  the PC, and the backend management UI should take it from Tailscale's own
  display, not from the PC's interface list. Likewise a phone that reaches such an
  address outside the tailnet would send its token there.
- **The hub must never be exposed beyond the tailnet**: no Tailscale Funnel or
  Serve, no port forwarding, no reverse proxy. Its security rests on WireGuard
  around every byte; anything that removes the tunnel removes the encryption.

### Limits

A bad or broken client must not exhaust the PC, which runs the user's desktop app
in the same process.

**The hub's socket layer is the JDK's blocking sockets, and the server module is
JVM-only.** A first version used Ktor's raw sockets (`ktor-network`) under an accept
loop of its own. Review showed that it could not be bounded: Ktor's socket writer
takes a whole answer at once, so writing "finished" while a client read nothing; its
`dispose()` left the descriptor open and the writer spinning a core until the client
closed; and 40 clients that never read left about 40 descriptors, 450 to 500 MiB of
heap and about 700% CPU behind, while the hub reported no open connections. Since the
hub runs only inside the desktop app's JVM, it now uses `java.net.ServerSocket` and
`Socket`, with one thread per connection under the connection cap:

- `Socket.close()` frees the descriptor at once and ends any blocked read or write
  on it, on its thread, with an exception. That is the one operation everything
  else rests on, and it is the JDK's.
- A blocked thread uses no CPU. With at most 16 connections, at most 16 threads, an
  accept thread and a watch thread exist.
- Answers are written in 64 KiB parts to a blocking stream, so a write blocks while
  the client does not read, and the hub knows exactly how far each answer got.
- A watch thread looks at every connection every 50 ms and closes the socket of one
  that is past its head's time, past the idle time since bytes last moved in either
  direction, or past its request's deadline.

NIO with a selector would need fewer threads, but its correctness is harder to show,
and 16 threads cost little. Ktor's HTTP/1.1 parser (`ktor-http-cio`) still parses
request heads, and the protocol is unchanged. The server keeps its own loop rather
than an HTTP server library for the reasons above: no library offered all of these
limits together.

**ADR-0006.** The `server` module leaves the linuxX64 rule, as an app module may:
it is not shared, has no caller outside the desktop app (or a later JVM CLI
command), and uses JDK sockets. `backend:peer`, the client, stays multiplatform and
is tested on the JVM, the Android host and linuxX64. A native CLI that wanted to run
the hub would need its own socket layer.

Defaults, in `HubLimits`:

| Limit | Default | What it bounds |
|---|---|---|
| `maxConnections` | 16 | Sockets and threads. A connection's slot comes back only after its socket is closed. The accept thread takes the next connection only when a slot is free, so further clients wait in the kernel's listen queue, not in memory |
| `maxConnectionsPerClient` | 8 | Connections from one address, with a token or not. Further ones are closed at once, so one tailnet node cannot take every slot |
| request head | 16 KiB, in `headerTimeout` = 10 s | Memory and time before authentication |
| body | 32 MiB, with a plain `Content-Length` | Checked before reading; chunked bodies are refused (411) |
| `idleTimeout` | 30 s | A body, or an answer, that moves no bytes for this long is cut, and nothing is written |
| `requestDeadline` | 15 min | One request in all, from its first byte until the client has taken the whole answer; 32 MiB at about half a megabit per second |
| `maxTransfers` | 4 | Files held in memory at once. A read keeps its transfer until its answer is written or its socket is closed |

A body is read whole into memory and only then passed to `writeNew`, so a body
that breaks off or stalls writes nothing. Answers are not streamed from the store:
`BlobStore.read` returns a whole file, which the hub holds while writing it from the
same array. Memory for files is so bounded by `maxTransfers` × 32 MiB. A client that
never reads its answer holds a slot and a transfer until the idle time or the
deadline, and then its socket is closed and both come back.

Each connection runs on its own thread; an exception or `Error` there, such as
running out of memory, ends that connection only. If the accept thread ends for any
reason other than `stop`, the status becomes `Failed` with the reason, and `start`
serves again. `stop` closes the listening socket and every connection's socket, and
waits at most five seconds for their threads, which closing has already woken.

The client bounds what it reads the same way: 32 MiB for a file or a listing, and
64 KiB for an error.

### Paths

The hub checks every path with `requireValidBlobPath` before it reads a byte of a
body, and passes it to a `LocalFolderBlobStore`, which checks again and walks the
path one segment at a time without following symbolic links
([ADR-0012](0012-local-folder-publishing.md)). So the hub's path rules are exactly
the local folder's, and no client can leave the store's root.

### The backing store, and the PC's own sync

The hub serves a dedicated folder on the PC (`HubConfig.storeRoot`, for example
`~/.local/share/jotter/hub`) through `LocalFolderBlobStore`. The PC's own sync uses
**the same folder directly**, as an ordinary local folder backend, not through
HTTP. So the PC is one more device on its own hub: it pushes its segments there,
reads the phone's, and relays between the hub and its other backends, such as
WebDAV, as [PLAN.md §6.3](../PLAN.md#63-sync-cycle-per-backend) allows.

Writers in the hub and in the PC's own sync can race for a path. ADR-0012 covers
that: within one process the local folder's mutex and hard link or lock decide,
and across processes on one machine its hard link or `fcntl` lock does. The
contract suite's races and `HubLimitsTest` race clients through the hub against
the PC's own store.

`PeerHub.setUpStore()` creates the folder and its marker once, when the user turns
the hub on, never because `ping` said `NOT_INITIALIZED` (ADR-0012). Until then
devices are told that the hub's store is not set up.

### How it runs

`PeerHub` is a library in the `server` module with a small API for whatever hosts
it: `setUpStore`, `start`, `stop`, `status` (a `StateFlow` of `Stopped`,
`Running(host, port)` or `Failed(message)`), and `pair`, `pairedDevices` and
`unpair`.

- **Inside the desktop app** by default: the app starts the hub at launch if the
  user has turned it on, and stops it on quit. No second process, no service file.
- **As a systemd user service** for a PC that should serve without the app open:
  a `jotter hub` command in the CLI, run by a user unit. Not built yet.

Both use the same folder and token file, so either can serve; a second hub on the
same port fails to start with `HubStartException`. The hub starts only when the
user turns it on. Port 47734 is the default.

### How a device finds the hub

The user types the PC's Tailscale address or MagicDNS name, and the port if it is
not 47734, into the device (`PeerAddress.parse`), with the token from pairing.
There is no discovery, broadcast or QR code in this decision.

## Consequences

### Positive

- The contract suite runs end to end, client to server to local folder, on the
  JVM, with the hub stopped and with its folder gone. Through the hub, a store
  behaves exactly as a local one. The client's own tests run on the JVM, the
  Android host and linuxX64.
- Descriptors, threads, CPU and memory of the hub are bounded and measured in tests
  with clients that never read.
- Nothing reaches the store without a token, a lost device is unpaired alone, and
  the hub keeps no secret worth stealing.
- The hub cannot be bound to the LAN or the internet by a configuration slip.
- No certificates to create, trust, renew or pin.
- The server is small: four requests, one per connection, and every limit
  explicit.
- `core:sync` needed no change.

### Negative

- Without Tailscale, the hub is reachable from the PC only. A user without
  Tailscale cannot use it from a phone.
- Without TLS, anything between the two WireGuard ends is trusted: a process on
  either machine that can read the loopback or tailnet traffic sees tokens and
  notes. The same process could read the notes on disk.
- A hand-written accept loop and request handling is code to maintain and get
  right, though the parsing is Ktor's.
- The hub is JVM-only: a native CLI could not run it without a socket layer of its
  own (ADR-0006 exemption above).
- One connection per request costs a TCP handshake per file; a first sync of
  thousands of segments opens thousands of connections.
- Slow clients on two or more addresses can hold every connection for up to the
  header timeout or the request deadline (only paired devices get past the head).
  That blocks the hub, not the PC.
- A token is long to type on a phone. The backend management UI may want a QR code
  or a link format, which this ADR leaves open.
- `100.64.0.0/10` does not prove Tailscale (see "An allowed address on another
  interface"), and the hub cannot listen on IPv6.
- Answers are buffered, not streamed, so a read costs up to twice the file in memory.
- A client on several addresses of one machine gets a per-client cap for each.

### Not verified

- No run over a real tailnet, or from an Android device. All tests are on loopback.
- `testAndroidHostTest` runs the client on a host JVM, not on Android's network
  stack.
- On linuxX64, Ktor's CIO client does not apply its socket timeout (seen in
  `PeerBlobStoreTest`); the client's own idle watch covers that. On the JVM the
  engine passes a slow body on in batches, so a body that trickles in a few bytes
  at a time could look idle for longer than it is. Tested with 256 KiB parts.
- Name resolution was tested with IP literals and an unresolvable name only, not
  with a MagicDNS name.
- The claim that WireGuard makes TLS redundant rests on Tailscale's design, not on
  a test. That Tailscale gives the PC an address in `100.64.0.0/10` is from its
  documentation.
- The systemd user service and the CLI command do not exist.

## Alternatives considered

- **TLS with a self-signed certificate pinned at pairing:** protects even off
  Tailscale, but every device must pin and the PC must keep a key, and with the
  address rule it protects nothing more. Can be added as protocol v2 if the hub
  ever listens off the tailnet.
- **Tailscale's own identity** (`tailscale whois`, or `tsnet` inside the app):
  would make tokens unnecessary between the user's own nodes, but ties the hub to a
  Tailscale API and daemon, and `tsnet` is a Go library.
- **One shared pairing secret:** simpler, but a lost device means re-pairing all.
- **Ktor's raw sockets under our own loop:** the first version. Not chosen after
  review: a socket could not be truly closed, and answers could not be held
  back, as "The hub's socket layer" says.
- **Ktor's server engine (CIO) with routing:** less code, but no cap on
  connections and no time limit for the request head; an idle or slow client
  costs a coroutine and a socket for as long as it likes.
- **A refused token as `Unreachable(FAILED)`:** kept `backend:api` unchanged, but
  sync would retry a token that can never work, and the UI could tell the case
  only by its text.
- **Streaming answers:** would bound memory per connection, but `BlobStore.read`
  returns a whole file; the transfer limit bounds memory instead.
- **Letting the user allow other hosts for the token:** not now; a hub off the
  tailnet would have no encryption.
- **Keep-alive and pipelining:** fewer handshakes, but a connection could then
  carry many requests and the limits would need to be per request and per
  connection.
- **Discovery** (mDNS, Tailscale's peer list): convenient, but more surface and
  dependencies for typing an address once.
