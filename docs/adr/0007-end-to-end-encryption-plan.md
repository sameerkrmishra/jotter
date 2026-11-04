# 0007. End-to-end encryption: a keyed codec per backend, a wrapped store key, and a new store to migrate

- **Status:** Proposed
- **Date:** 2026-11-04

**Not built in v1.** v1 writes plain JSON, gzipped, to every backend. This record decides
how encryption is added later, and what v1 must keep so that it can be. No code changes
with it.

## Context

End-to-end encryption of data on backends is deferred
([PLAN.md §1](../PLAN.md#1-requirements-summary)), and §6.6 kept a seam for it: segments
pass through a `SegmentCodec`, so encryption "becomes a codec without changing backends or
the merge logic". The people it protects against are those who can read a backend but not
a device: the WebDAV provider, a NAS admin, whoever finds the USB stick, a backup of the
PC hub's folder ([ADR-0015](0015-pc-hub-peer-protocol.md)). Today all of them can read
every note and its whole history.

What is on a backend now ([ADR-0004](0004-immutable-change-segments.md),
[ADR-0014](0014-sync-failure-handling.md), [ADR-0017](0017-webdav-backend.md)):

| Path | Content | Written by |
|---|---|---|
| `.jotter-store` (folder, WebDAV) | Empty marker | The backend, once |
| `jotter/formats/1-json+gzip.json` | `{"format":1,"codec":"json+gzip"}` | `SyncEngine`, from constants |
| `jotter/devices/<deviceId>/device.json` | Device name, platform, creation time, plain JSON | `SyncEngine`, directly |
| `jotter/devices/<deviceId>/<number>-<hash>.json.gz` | Gzipped segment JSON | `Pusher`, through the codec |

Device ids are random UUIDv4 and carry nothing. The segment JSON holds every revision in
full: note ids, revision ids (UUIDv7, so creation times), stamps, title, body, tags.

### The seam, checked against the code

`core:sync`'s `SegmentCodec` has `encode`, `hash` and `decode`, and `Pusher` and `Puller`
reach segments only through it. Merge logic never sees bytes. So far §6.6 holds. But:

1. **The name hash is a plaintext fingerprint.** `JsonGzipCodec.hash` is the first 16 bytes
   of an unkeyed SHA-256 of the segment JSON, and ADR-0014 and PLAN.md §6.2 fix that as the
   rule. Anyone who can guess a segment's JSON can confirm the guess from its name. Guessing
   is hard (random ids, stamps), but it is a leak by design, and §6.2's rule cannot stay for
   an encrypted store. It is a method of the codec, so the seam can change it.
2. **`device.json` and the format file bypass the codec.** `SyncEngine` writes both itself.
   `device.json` gives the device's name ("Sameer's Pixel") and creation time in plain text.
3. **The codec does not name itself.** The format file's name comes from the constant
   `SYNC_CODEC`, not from the codec in use. A test that injects another codec through
   `SyncSeams` still writes `1-json+gzip.json`.
4. **One codec per engine, not per backend.** `SyncSeams` is internal, and the public
   `SyncEngine` constructor always uses `JsonGzipCodec`. A device that moves from a plain to
   an encrypted store needs both at once, one per backend.
5. **The codec must be deterministic.** Its KDoc says "the same segment always gives the same
   content", and ADR-0014's "a path has one possible content" and ADR-0017's tolerance of
   Apache's racing `PUT`s rest on it: a repair writes a lost segment again by encoding it
   again. PLAN.md's example, XChaCha20-Poly1305 with a random nonce, breaks that.
6. **The format is read before the hash is checked.** `JsonGzipCodec.parse` reads the format
   number out of the plaintext, to tell a newer format from damage. Encrypted, nothing may be
   parsed before it is authenticated, and a key the device lacks is a third case.
7. **The codec has no key.** It is constructed without one, and nothing in a file says which
   key it needs.

None of these touches the merge logic, revision bytes or `BlobStore`. They are all in
`core:sync`'s segment layer and `SyncEngine`, which is where the change belongs.

## Decision

**Threat model.** Protected: the content of notes and their history against anyone who can
read or write a backend but holds no key. Detected: any change to an encrypted file.
Not protected: availability (a backend can withhold or delete files, as today), the shape
of the data (below), and anything on a device: the SQLite database stays plain, as do
exports and the logs ([ADR-0001](0001-local-first-architecture.md)). Disk encryption is
the operating system's job.

**Where encryption plugs in.**

| Item | Encrypted store |
|---|---|
| Segment content | Encrypted and authenticated with the store key |
| Segment name | `<number>-<hash>`, the hash keyed: HMAC-SHA-256 of the JSON with a name key, first 16 bytes |
| `device.json` | Sealed like a segment, or not written. Sync never reads it |
| Format file | Plain, as now, with a new codec name: `jotter/formats/1-aead1.json` |
| Key files | New, plain: `jotter/keys/<keyId>-<hash>.json` |
| Device folder names | Plain random UUIDv4, as now |
| Revision ids, stamps, merge ids | Unchanged, inside the ciphertext only |
| Markers | Unchanged, empty |

**The codec, `aead1`.** It wraps v1's bytes: the plaintext is exactly what `JsonGzipCodec`
writes today, so compression, the JSON and its limits are unchanged. A file is a fixed
header (magic `JOTE`, a version byte, the 16-byte key id, a 12-byte nonce), then the
ciphertext and its tag. The header is the associated data.

- **The cipher is AES-256-GCM-SIV** ([RFC 8452](https://www.rfc-editor.org/rfc/rfc8452)),
  under the content key, with the header as associated data.
- **Deterministic.** The nonce is the first 12 bytes of HMAC-SHA-256 under the nonce key,
  over the plaintext. The same segment gives the same file, so the codec keeps its KDoc's
  promise, a repair writes the same bytes, and ADR-0017's two winners still wrote one thing.
  What it reveals is equality of two files, which their names already reveal. GCM-SIV is
  built for a repeated nonce: should two different plaintexts ever get one nonce, that too
  reveals only whether they are equal, where plain GCM would give away its authentication
  key.
- **Three separate keys** come from the store key by HKDF-SHA-256, each with its own fixed
  label: `jotter-aead1 content`, `jotter-aead1 nonce`, `jotter-aead1 name`. No key is used
  for two purposes.
- **Where the primitives come from:** GCM-SIV and Argon2id are not in the JDK, so Bouncy
  Castle (pure Java) on the JVM and Android, and OpenSSL 3.2 or later on `linuxX64`, which
  has both. HMAC and HKDF are in all three. No native library is bundled
  ([ADR-0006](0006-native-compatible-shared-modules.md), F-Droid). This replaces PLAN.md's
  XChaCha20 example.
- **Decoding order:** header; key id (unknown: the new `SegmentProblem.NO_KEY`, unread and
  tried again, like `FUTURE_FORMAT`); authenticate and decrypt (failure: `CORRUPT`); inflate;
  format number; keyed name hash; device and number against the folder, as `Puller` does
  now. Nothing is parsed before it is authenticated.
- **Pinned** like the merge hash: a test vector of a whole file, checked on the JVM, Android
  and `linuxX64`, against a second implementation (Python's `cryptography`).

**Keys.** A **store key** is 32 random bytes with a random 16-byte key id. One person has one
store key for all their backends, so there is one passphrase; each encrypted backend holds
its own key file. A key file is plain JSON: the key id, the KDF and its parameters, a random
16-byte salt, a random 12-byte nonce, and the store key wrapped with AES-256-GCM under the
key from the passphrase, with the key id as associated data. Salt and nonce are new for each
key file; a key file is written once, so a random nonce does no harm there. The KDF is
Argon2id. Setup never goes below RFC 9106's second recommended setting, t = 3 passes,
m = 64 MiB, p = 4 lanes, and raises t as far as a phone bears in about a second. A device
refuses, as damaged, a key file that asks for less than that minimum or more than a cap
(t ≤ 10, m ≤ 256 MiB, p ≤ 8), so a hostile key file cannot exhaust a phone or ask for a key
that is cheap to guess. Key files are written once with `writeNew` and named by their
content, like everything else. Several may exist for one key id.

**On a device** the store key is a secret like any other
([ADR-0016](0016-where-secrets-live.md)): the Secret Service on Linux, or the 0600 file the
user chose; the Keystore-sealed file in `noBackupFilesDir` on Android
([ADR-0018](0018-android-app.md)). A new `SecretKind.STORE_KEY`, named by key id rather than
by backend. Never in the database, a log, an export or an Android backup, so a copied or
restored database asks for the passphrase. The passphrase itself is never stored. Background
sync uses the stored key and never needs the user, as ADR-0016 requires. On Linux with the
file fallback the key sits in plain text in `~/.config`: encryption then protects the
backend, not the PC's home directory, and the status screen says so.

**Adding a device.** Add the backend as today, then enter the passphrase: the device reads
the key files, unwraps the store key and stores it. A device that already has the key can
also show it as a recovery code (base32, about 52 characters) or a QR code to type or scan;
nothing travels over the network. Until it has the key, a device leaves every file
`NO_KEY` and pushes nothing there.

**Recovery.** Every device holds all notes in plain text (ADR-0001), so a device that loses
its key enters the passphrase again, and one that is lost costs nothing but its unsynced
edits. The data on backends is lost only if every device and the passphrase are lost. Setup
offers the recovery code to write down, and says this in plain words.

**Rotation.** A new store key with a new key id, wrapped under a new passphrase, in new key
files. New segments use it; old files keep the old key, as nothing is rewritten. The new key
is never wrapped under the old one, so a device that has only the old key, such as a lost
phone, cannot follow; the user enters the new passphrase on each remaining device, and
revokes the lost one's backend access where that is possible (hub `unpair`, a new WebDAV app
password).

**After a rotation the old key is read-only.** The rotating device writes a *rotation record*
on each backend, sealed under the new key: the retired key id and the names of every file
under that key on that backend at that moment. From then on a device accepts a file under a
retired key only if a rotation record names it; any other is left unread as `RETIRED_KEY`, a
new `SegmentProblem`. So a lost device that still holds the old key cannot add files that
count, even where its access cannot be revoked, as on a USB stick or a local folder. A
rotation record is itself a file under the new key, so a later rotation names it in turn, and
the chain holds. Two rotations from the same old key both stand: a file counts if either
names it.

- **What it costs:** a device that was offline across a rotation may hold segments under the
  old key that are recorded, or even published, but named in no rotation record. Once it has
  the new key it gives those segments up (ADR-0014's rule for a segment that cannot be
  published), and pushes their revisions again under the new key. Until the user enters the
  new passphrase there, its new edits reach no one.
- **The limit, plainly:** a revoked device can still read everything written up to the
  rotation, on any backend it can reach, and it already holds every note of that time in
  plain text. It can also still delete files where its access cannot be revoked. Rotation
  protects only what comes after.

Devices keep every old key to read old files. A **passphrase change** alone writes a new key file for the
same key; the old file stays, so the old passphrase still opens the key for anyone who holds
it and the backend. Retiring old material for good is a new store (below).

**What a backend operator still learns.** The number of devices and their random ids; how
many segments each wrote, when (file times, request times), and how large; so roughly how
much and when each device edits, and a single small edit's size is close to the note's
size. Compression before encryption makes size depend on content too. The key files' salt
and KDF parameters, which allow an offline guess at the passphrase at Argon2id's cost: a weak
passphrase is the weakest point, and setup should insist on a strong one or the generated
code. The operator cannot read text, tags, note counts or which notes changed, cannot
forge or alter a file unnoticed, and replaying an old file does nothing (apply is
idempotent). Padding segments to fixed sizes is left out: it costs space on every backend
and hides little while timing shows activity.

**Migration of an existing store.** Not in place. v1's files are immutable and plain, and a
store with two codecs' format files is `Incompatible` to every version. Turning encryption
on creates a **new** backend location (another folder or WebDAV collection) with the
encrypted codec, seeded with the full history like any added backend (PLAN.md §6.5). Each
other device enters the passphrase and adds it. Then the user removes the plain backend on
every device and deletes its files by hand, which the app says, as v1 has no "delete remote
data". While any plain backend stays configured, the app warns: a device that syncs both
relays every new note into the plain one, by design.

**Merge format and convergence.** Unchanged. Encryption sits below the segment: the revisions
a codec decodes are the same objects as today, merge ids still hash plaintext canonical bytes
([ADR-0013](0013-merge-base-and-covered-heads.md)), and the merge rules
([ADR-0011](0011-touching-changes-and-merge-format.md)) and validity rules (ADR-0014) read
only revisions. Convergence needs every device of one store to hold its keys; one that does
not is behind, as with a future format, and shows what it lacks as hidden heads. Different
backends may have different codecs or keys, because `Pusher` encodes per backend; a relay
decrypts with one and encrypts with the other.

**What v1 already preserves.**

- Every segment byte goes through `SegmentCodec`, and merge logic never sees a file.
- The format file's name carries the codec, and a v1 device that finds another codec's file
  treats the backend as `Incompatible` and writes nothing to it: an old app never pushes
  plain segments into an encrypted store.
- Content naming and `writeNew` already give every path one content; a deterministic codec
  keeps that.
- Secrets never go in the database; the `SecretStore` seam exists on every platform.
- Unknown folders (`jotter/keys`) are ignored by v1's listing.

**What the later change breaks or changes** (all inside `core:sync`, plus a format record):
`SegmentCodec` gains a name and a key lookup, and moves from `SyncSeams` to `SyncTarget` so
each backend has its own; `SYNC_CODEC` goes; `device.json` goes through the codec;
PLAN.md §6.2's name rule becomes "the codec's name hash"; new `SegmentProblem`s `NO_KEY`
and `RETIRED_KEY`; `SecretKey` names a key id, not only a backend. No backend, no
`BlobStore` method, no merge rule and no revision byte changes.

## Consequences

### Positive

- A backend, the hub's folder or a lost USB stick no longer shows any note.
- Tampering with an encrypted file is detected before it is parsed.
- Merge rules, convergence tests and pinned histories carry over unchanged.
- Background sync keeps working without the user, on every platform.
- Plain and encrypted backends can coexist on one device during migration.

### Negative

- Metadata stays visible: device count, activity times, sizes.
- Losing every device and the passphrase loses the backend's copy for good.
- Key files allow offline passphrase guessing.
- Rotation and passphrase changes protect the future only: a revoked device reads
  everything up to the rotation, and old files and old key files stay until the whole store
  is replaced by hand.
- Migration costs a second store, a full upload, a passphrase on every device, and manual
  deletion of the old files.
- GCM-SIV and Argon2id need Bouncy Castle on the JVM and Android, a new dependency, and
  OpenSSL 3.2 or later on `linuxX64`.
- A device offline across a rotation must re-encrypt and push again what it had not
  published in time.
- With the Linux file fallback, the store key is in plain text in the home directory.

## Alternatives considered

- **Rotation that leaves the old key writable:** simpler, and no device has to push again,
  but a lost device could go on adding files that count to any backend whose access cannot
  be revoked.
- **Derive the encryption key from the passphrase directly**, as PLAN.md's example implied:
  no key file, but a passphrase change then means re-encrypting everything, which immutable
  files forbid, and there is nothing to show as a recovery code.
- **AES-SIV ([RFC 5297](https://www.rfc-editor.org/rfc/rfc5297)):** deterministic by
  itself, with no nonce key, and in Bouncy Castle and OpenSSL too. Not chosen because its
  CMAC is a serial pass over the whole segment, up to 32 MiB, where GCM-SIV's POLYVAL uses
  the carry-less multiply that phones and PCs accelerate, as for GCM. With the nonce derived
  by HMAC, GCM-SIV is just as deterministic, and keeps its misuse resistance as a second
  line.
- **AES-256-GCM with the HMAC-derived nonce:** in the JDK, so no Bouncy Castle for the
  cipher, but a home-made SIV, and a repeated nonce would be fatal rather than harmless.
- **A random nonce (XChaCha20-Poly1305 or AES-GCM):** standard, but the same segment would
  give different files, against the codec's contract and the reasoning of ADR-0014 and
  ADR-0017. A synthetic nonce costs one HMAC.
- **Keep the plain SHA-256 in names:** no change to §6.2, but a fingerprint of the plaintext
  on every file.
- **Random file names:** hide even equality, but lose content naming, which ADR-0014's
  repairs and restored backups depend on.
- **Encrypt in a wrapping `BlobStore`:** no change to sync at all, but names keep the plain
  hash, the format file would be encrypted under the v1 name and read as damaged, and the
  backend layer would have to interpret paths.
- **A key per backend:** contains one backend's leak, but every backend needs its own
  passphrase or a key hierarchy, and the keys all sit on the same devices anyway.
- **Encrypt the existing store in place:** impossible without overwriting files, and the
  plain copies would stay on the backend's own backups.
- **age or OpenPGP files:** proven formats, but no Kotlin Multiplatform implementation and
  random per-file keys, so not deterministic.
- **Pad every segment to a size class:** hides sizes, at a large cost for small segments,
  while timing still shows activity.
