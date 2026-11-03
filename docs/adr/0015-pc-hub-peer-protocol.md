# 0015. The PC as hub: protocol, security, how it runs and where it stores

- **Status:** Proposed
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
([ADR-0006](0006-native-compatible-shared-modules.md)), so the client and the
server must as well, and must be tested on every target.

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
  `MISSING`, `NOT_INITIALIZED` or `FAILED`). A hub that cannot be reached, or that
  refuses the token, is `Unreachable(FAILED)` with a detail that says which. The
  `BlobStore` API has no reason for "refused"; see "Alternatives".

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
- `100.64.0.0/10` is the shared address space of carrier-grade NAT too, so the rule
  cannot prove that an address belongs to Tailscale; it keeps out the usual LAN and
  public addresses. IPv6 (Tailscale's `fd7a:115c:a1e0::/48`) is not supported yet.
- Exposing the hub to the internet with Tailscale Funnel is not supported.

### Limits

A bad or broken client must not exhaust the PC, which runs the user's desktop app
in the same process. The hub's server is an accept loop of its own over Ktor's
sockets (`ktor-network`) and HTTP/1.1 parser (`ktor-http-cio`), because Ktor's CIO
server engine has no limit on connections and no time limit for a request's
headers. Defaults, in `HubLimits`:

| Limit | Default | What it bounds |
|---|---|---|
| `maxConnections` | 16 | Sockets. The loop accepts the next connection only when one ends, so further clients wait in the kernel's listen queue, not in memory |
| request head | 16 KiB, in `headerTimeout` = 10 s | Memory and time before authentication |
| body | 32 MiB, with a plain `Content-Length` | Checked before reading; chunked bodies are refused (411) |
| `idleTimeout` | 30 s | A body that stalls is dropped, and nothing is written |
| `requestDeadline` | 15 min | One request in all; 32 MiB at about half a megabit per second |
| `maxTransfers` | 4 | Files held in memory at once, so at most 4 × 32 MiB |

A body is read whole into memory and only then passed to `writeNew`, so a body
that breaks off or stalls writes nothing. The client bounds what it reads the same
way: 32 MiB for a file or a listing, and 64 KiB for an error.

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
  JVM, the Android host and linuxX64, with the hub stopped and with its folder
  gone. Through the hub, a store behaves exactly as a local one.
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
- One connection per request costs a TCP handshake per file; a first sync of
  thousands of segments opens thousands of connections.
- 16 slow clients can hold every connection for up to the header timeout or the
  request deadline (only paired devices get past the head). That blocks the hub,
  not the PC.
- A token is long to type on a phone. The backend management UI may want a QR code
  or a link format, which this ADR leaves open.
- A refused token reads as `Unreachable(FAILED)`, told apart only by its detail
  text, so the status screen cannot react to it by type.
- `100.64.0.0/10` does not prove Tailscale, and IPv6 is not supported.

### Not verified

- No run over a real tailnet, or from an Android device. All tests are on loopback.
- `testAndroidHostTest` runs the client on a host JVM, not on Android's network
  stack.
- On linuxX64, Ktor's CIO client does not apply its socket timeout (seen in
  `PeerBlobStoreTest`), so only the request deadline (15 min) bounds a hub that
  stops answering there. The JVM and Android are bounded by the socket timeout
  (60 s).
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
- **Ktor's server engine (CIO) with routing:** less code, but no cap on
  connections and no time limit for the request head; an idle or slow client
  costs a coroutine and a socket for as long as it likes.
- **A new `UnreachableReason.UNAUTHORIZED` in `backend:api`:** lets the UI tell a
  refused token by type. Left out to keep this change out of `backend:api` and
  `core:sync`; it is a small change for the backend management task to make.
- **Keep-alive and pipelining:** fewer handshakes, but a connection could then
  carry many requests and the limits would need to be per request and per
  connection.
- **Discovery** (mDNS, Tailscale's peer list): convenient, but more surface and
  dependencies for typing an address once.
