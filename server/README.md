# server

The PC as hub ([PLAN.md §6.4](../docs/PLAN.md#64-the-pc-as-hub)): serves a folder
on the PC to the user's other devices, over HTTP on loopback or Tailscale, as a
`BlobStore` that [`backend:peer`](../backend/peer/README.md) reaches. Transport,
security, limits and how it runs are
[ADR-0015](../docs/adr/0015-pc-hub-peer-protocol.md).

```kotlin
val hub = PeerHub(HubConfig(storeRoot = hubFolder, tokenFile = devicesFile, host = "100.101.102.103"))
hub.setUpStore()                 // once, when the user turns the hub on
hub.start()                      // HubStatus.Running(host, port), or HubStartException
val pairing = hub.pair("Pixel")  // show pairing.token once; the hub keeps a hash
hub.status                       // StateFlow<HubStatus> for the UI
hub.unpair(pairing.device.id)    // that token stops working at once
hub.stop()
```

The PC's own sync uses the same folder directly, as a `LocalFolderBlobStore`
backend, so the PC is a device on its own hub.

**Dependency rule:** depends on `backend:peer` (the protocol), `backend:localfolder`
(the store), Ktor's sockets and HTTP parser, okio, atomicfu, coroutines and
kotlinx.serialization. No other Jotter module, and no app module depends on it
yet.

## API

| Type | What it is |
|---|---|
| `PeerHub` | `setUpStore`, `start`, `stop`, `status`, `pair`, `pairedDevices`, `unpair` |
| `HubConfig`, `HubLimits` | Folder, token file, address, port, and the limits |
| `HubStatus` | `Stopped`, `Running(host, port)` or `Failed(message)` |
| `PairedDevice`, `Pairing` | A device, and a new device with its token |
| `HubStartException` | The port is taken, the address is not this machine's, or the token file is damaged |
| `isAllowedBindAddress` | Whether the hub may listen on an address |

## Behaviour

- **Addresses.** Only an IPv4 literal in loopback (`127.0.0.0/8`, the default) or
  Tailscale's range (`100.64.0.0/10`). `0.0.0.0`, LAN addresses and host names are
  refused by `HubConfig`. No TLS: WireGuard encrypts the tailnet.
- **Tokens.** Every request needs one, checked before anything else, in constant
  time against every device. The token file holds SHA-256 hashes only, and is
  replaced atomically. A damaged or unreadable file stops `start` and `pair`, and
  is never replaced.
- **Requests.** One per connection; the hub closes after answering. Unknown
  requests, repeated or extra parameters, a body on a read and a second
  `Authorization` header are refused. A write needs a plain `Content-Length` of at
  most 32 MiB, checked with the path before the body is read.
- **Limits** (`HubLimits`): 16 connections (more wait in the kernel's queue), a
  16 KiB head within 10 s, 30 s of silence in a body, 15 min per request, and 4
  files in memory at once. A body that stalls or breaks off writes nothing.
- **Errors.** A store failure goes to the client as its code. An unexpected
  exception is answered as `FAILED` without its message, and never stops the hub.
- **No logging.** The hub logs nothing, so it cannot log a token.

## Design

- **`PeerHub`** is the lifecycle and the API. **`PeerServer`** is the accept loop:
  it takes a connection only when it has a slot, and drops a connection on any
  timeout. **`HttpExchange`** reads the head (bounded, then parsed by Ktor's
  `parseRequest`) and the body, and writes answers. **`PeerHandler`** checks the
  token and the request and calls the store. **`DeviceTokens`** keeps the paired
  devices.
- **Not Ktor's server engine**: it has no limit on connections or on the time to
  send headers (ADR-0015).
- **Common code only**, on the JVM, Android and linuxX64, like the shared modules
  ([ADR-0006](../docs/adr/0006-native-compatible-shared-modules.md)).

## Tests

All run on the JVM (counted by the coverage gate), the Android host and linuxX64,
with real hubs on `127.0.0.1` and free ports:

- `PeerContractTest` and `PeerStoreGoneContractTest`: the whole contract suite
  through `PeerBlobStore` to a running hub, with the hub stopped and with its
  folder deleted as the two ways of going away.
- `HubAuthenticationTest`: wrong, missing and revoked tokens on every method,
  nothing written, and no answer that holds a token.
- `HubLimitsTest`: 32 MiB exactly and one byte more, chunked and odd lengths,
  oversized heads, stalled heads and bodies, broken bodies, the connection and
  transfer limits, and five writers racing through the hub and on the PC.
- `HubProtocolTest`: requests outside the protocol, heads that do not parse, paths
  that try to leave the store, names with query characters, and failing stores.
- `PeerHubTest`: lifecycle, a taken port, an unset store, pairing across restarts,
  damaged token files, and the address rule.

`UnreadableTokenFileTest` (JVM only, as it needs `chmod`) checks that a token file
the hub may not read stops it and is never replaced. A read keeps its transfer
until its answer is sent; that is tested on `PeerHandler` alone, as a reader too
slow to drain the sockets cannot be made reliably.

Not tested: a real tailnet and an Android device.
