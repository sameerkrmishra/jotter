# 0004. Immutable, per-device change segments on dumb blob stores

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Devices exchange revisions ([ADR-0003](0003-revision-dag-and-three-way-merge.md))
through backends the user chooses: a local folder or USB stick, WebDAV
(mailbox.org, a NAS, Nextcloud), or the user's PC over Tailscale. More backends
(Dropbox, Google Drive, S3) may follow. Most of these offer no locking and no
atomic read-modify-write. Two devices that overwrite the same file will lose
data.

## Decision

Backends are reduced to a minimal **BlobStore** interface in `backend/api`
([PLAN.md §6.1](../PLAN.md#61-blobstore-interface)):

- `list(prefix)`, `read(path)`, `ping()`;
- `writeNew(path, bytes)`, which must fail if the path already exists.
  WebDAV implements it with `PUT` plus `If-None-Match: *`.

Implementations must not interpret file contents. A shared contract test suite
runs against every implementation; a backend is done when it passes.

Each backend uses this layout:

```
jotter/
├── format.json                  # protocol version
└── devices/<deviceId>/
    ├── device.json              # name, platform, created
    ├── 000000000001.json.gz     # change segment
    └── 000000000002.json.gz
```

A **segment** is an immutable batch of revisions, written by one device and
numbered sequentially per device. A device only writes into its own folder and
never modifies or deletes a file it has written. There are two exceptions:
when removing a backend the user may choose "also delete remote data"
([PLAN.md §6.5](../PLAN.md#65-adding-and-removing-backends)), and
`format.json` lives outside any device folder and is written once, when the
backend is initialised. Segments pass through a
`SegmentCodec` (v1: JSON + gzip), which is the seam for future encryption.

A sync cycle per backend:

1. **Push** local revisions not yet on the backend as a new segment. This may
   include revisions that came from other devices, so a backend can relay
   between devices that never share a backend directly.
2. **Pull** segments past the stored cursor for every device folder.
3. **Apply** in one transaction: insert revisions idempotently by revision id,
   recompute heads, merge, update `note` and FTS, advance cursors.

## Consequences

### Positive

- Safe on any store without locking: no file is ever written by two devices.
- A new backend is four methods plus passing the contract tests.
- Re-reading a segment, or receiving a revision through two backends, is
  harmless because apply is idempotent.
- Relaying lets the phone and a second PC converge through different backends.
- Segments are readable JSON once decompressed, which helps debugging.

### Negative

- The log only grows. Segments and tombstones stay until snapshot and
  compaction, which is deferred. Seeding a new backend pushes the full history.
- Pull must list every device folder, which gets slower as old devices pile up.
- Relayed revisions are stored more than once across backends.
- Correctness depends on backends honouring "fail if exists". A store that
  silently overwrites breaks the guarantee; the contract tests must catch it.

## Assumptions and open points

- PLAN.md does not say how ordinary (non-merge) revision ids are formed. We
  assume they are globally unique, generated offline (for example UUIDv7), so
  idempotent insert by id is safe.
- If a database file is copied to another machine and both copies keep the
  same device id, both would try to write the same segment numbers. If both
  copies push to the same backend, `writeNew` fails loudly instead of losing
  data. If they push to different backends, `writeNew` cannot see the
  collision; it surfaces later, when relaying brings two different segments
  with the same device id and number together. The app must give a copied
  database a new device id. PLAN.md does not specify this yet; it should be
  settled in M3.
- If a push fails after the write but before the local record, the next push
  will find the number taken. The device id alone cannot show that the
  existing segment is ours, because a copied database shares it. The device
  reads the segment and compares its revision ids (or content hash) with what
  it intended to push. On a match it records the push and moves on. On a
  mismatch it stops and reports a device-id collision to the user, rather
  than skipping its own revisions.

## Alternatives considered

- **One mutable file per note:** simple, but concurrent writers overwrite each
  other on stores without locking.
- **A single shared log file:** needs append or locking, which WebDAV and
  folder-sync tools do not provide safely.
- **A smart sync server:** would make the PC an authority and break the
  dumb-backend principle ([ADR-0001](0001-local-first-architecture.md)).
