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
- **The token goes to the address only.** No redirects are followed, nothing is
  retried, and the token is in no message. A token with characters no hub token
  has, such as a line break, is refused at once.
- **`writeNew` copies the bytes** before sending, and refuses more than 32 MiB
  without contacting the hub.
- **Timeouts:** 10 s to connect, 60 s without bytes, 15 min for one request. On
  linuxX64 Ktor's CIO engine does not apply the 60 s, so only the 15 min bounds a
  hub that stops answering there.
- A refused token reads as `Unreachable(FAILED)` with a detail that says so;
  `backend:api` has no reason for it.

## Design

- **The protocol lives here**, in `PeerProtocol`, so the server and the client
  cannot drift and the phone does not depend on the server.
- **`PeerAnswers`** reads answers; `PeerBlobStore` sends requests. Every call runs
  on its own dispatcher, so the client's timeouts run in real time also under a
  test's virtual time.
- **One engine everywhere**: Ktor's CIO client on the JVM, Android and linuxX64
  ([ADR-0006](../../docs/adr/0006-native-compatible-shared-modules.md)).

## Tests

`PeerBlobStoreTest` runs the client against `FakeHub`, a raw socket server on
loopback that answers as a broken hub would: no protocol header, a body cut short
or too long, no length, impossible listings, every error code with right and wrong
statuses, redirects, silence, and nothing listening. `PeerAddressTest` covers the
address. Both run on the JVM (counted by the coverage gate), the Android host and
linuxX64.

The contract suite runs through this client against a real hub in the `server`
module's tests. The coverage gate counts this module's own tests only.
