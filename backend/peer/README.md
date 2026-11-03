# backend:peer

A `BlobStore` on the user's PC, which runs the hub from the [`server`](../../server/README.md)
module, reached over Tailscale. Phones and other desktops use it to sync with the PC.
The protocol, its security and its limits are
[ADR-0015](../../docs/adr/0015-pc-hub-peer-protocol.md).

```kotlin
val store = PeerBlobStore(PeerAddress.parse("100.101.102.103"), tokenFromPairing)
store.ping()                // Reachable, or Unreachable with the hub's reason
store.list("jotter/devices")
store.close()               // when the backend is removed
```

**Dependency rule:** depends on `backend:api`, the Ktor client with its CIO engine,
coroutines and kotlinx.serialization. No other Jotter module. The `server` module
depends on this one for the protocol, never the other way round.

## API

| Type | What it is |
|---|---|
| `PeerBlobStore` | The store. Takes the hub's address and a device token. `AutoCloseable` |
| `PeerAddress` | Host and port, as the user typed them: `parse("host")` or `parse("host:port")` |
| `PeerProtocol`, `PeerError` | The protocol, shared with the server: paths, parameters, the version header, limits, error codes |
| `dto.BlobEntryDto`, `dto.ErrorDto` | The JSON of a listing and of an error |

## Behaviour

- **Never empty by mistake.** A hub that is off, unreachable, refuses the token or
  is not a hub (no `Jotter-Peer-Protocol` header) is `BackendUnavailableException`,
  and `ping` answers `Unreachable`. A body cut short is the same: never a shorter
  file or a shorter listing.
- **Strict answers.** A listing must be one a store could give: sorted, no repeats,
  valid paths below the prefix, sizes not negative. A body needs a
  `Content-Length` of at most 32 MiB. Anything else is `BlobStoreFailureException`.
- **The token stays in the tailnet.** Before each request the hub's name is
  resolved, and the request goes to that address only if it is loopback,
  `100.64.0.0/10` or `fd7a:115c:a1e0::/48`; otherwise the call fails, unreachable,
  with a plain message, and nothing is sent. No redirects are followed, nothing is
  retried, and the token is in no message. A token with characters no hub token
  has, such as a line break, is refused at once.
- **`writeNew` copies the bytes** before sending, and refuses more than 32 MiB
  without contacting the hub.
- **Timeouts:** 10 s to connect, 60 s for a whole `ping` or `list`, 15 min for a
  `read` or `writeNew`, and 60 s without bytes moving. The client keeps that last
  watch itself (`IdleWatch`), as Ktor's CIO engine ignores its socket timeout on
  linuxX64. Uploads go in 64 KiB parts, so a slow upload is not a stall.
- **A refused token** is `Unreachable(UNAUTHORIZED)`: the device must be paired
  again, and retrying does not help.

## Design

- **The protocol lives here**, in `PeerProtocol`, so the server and the client
  cannot drift and the phone does not depend on the server.
- **`PeerAnswers`** reads answers; `PeerBlobStore` sends requests. Every call runs
  on its own dispatcher, so the client's timeouts run in real time also under a
  test's virtual time.
- **One engine everywhere**: Ktor's CIO client on the JVM, Android and linuxX64
  ([ADR-0006](../../docs/adr/0006-native-compatible-shared-modules.md)).

## Tests

`PeerBlobStoreTest` also covers the idle watch (silence, a stalled body, a slow but
steady one) and the tailnet rule. It runs the client against `FakeHub`, a raw socket server on
loopback that answers as a broken hub would: no protocol header, a body cut short
or too long, no length, impossible listings, every error code with right and wrong
statuses, redirects, silence, and nothing listening. `PeerAddressTest` covers the
address. Both run on the JVM (counted by the coverage gate), the Android host,
linuxX64 and Android devices (`connectedAndroidDeviceTest`, in CI on API 26 and
36), where `FakeHub` listens inside the test app.

The contract suite runs through this client against a real hub in the `server`
module's tests. The coverage gate counts this module's own tests only.
