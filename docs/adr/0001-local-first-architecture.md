# 0001. Local-first architecture

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Jotter runs on an Android phone, a Linux desktop and a Linux CLI. The phone is
often offline or on a poor connection. The user's PC, which could act as a
server, is regularly switched off or asleep. Notes must still be readable,
editable and searchable at all times, and the user wants no dependency on a
vendor service (see [PLAN.md §1](../PLAN.md#1-requirements-summary)).

The user also wants a portable format: copying one database file to a flash
drive should be enough to take the notes elsewhere.

## Decision

Each device owns a full replica of all notes in its own SQLite database. The
local database is the source of truth for the UI. Every read and write the
user makes goes to it, and never waits on the network.

Sync is a background process. It runs on app start, after edits (debounced)
and periodically. It exchanges changes with one or more user-chosen backends:
a local folder, WebDAV, or the user's PC over Tailscale. A backend that cannot
be reached is skipped and retried later. Sync never blocks the user.

Backends are dumb. They list, read and write files and nothing else. All merge
logic runs on the devices ([ADR-0003](0003-revision-dag-and-three-way-merge.md),
[ADR-0004](0004-immutable-change-segments.md)). The PC can act as a hub, but it
is just another backend, not an authority.

## Consequences

### Positive

- The app works fully offline on every device. Start-up, editing and search
  have no network latency.
- No central server is required. When the PC is off, the phone keeps working
  and both converge on the next successful sync.
- The user can choose, add and remove backends freely. No backend is special,
  so there is no lock-in.
- A copy of the `.sqlite` file is a complete, portable backup.
- Adding a backend is small: it only needs to store files.

### Negative

- Every device stores the full history of every note. For thousands of short
  notes this is a few MB, but the revision log grows until compaction
  (deferred, see [PLAN.md §1](../PLAN.md#1-requirements-summary)).
- Merging concurrent edits is the devices' job. This needs a careful,
  deterministic merge design and heavy testing (ADR-0003).
- Devices can disagree for a while. Convergence happens only after they have
  exchanged changes through some backend.
- Conflicts that cannot be merged automatically must be shown to the user.
- No server-side features such as search across devices or access control.
  Multi-user support is deferred and will need its own ADR (ADR-0008).

## Alternatives considered

- **Central server as source of truth (classic client/server):** simple
  consistency, but the PC that would host it is often off, and the phone would
  lose editing or need an offline cache with its own merge logic anyway.
  A hosted server would add a vendor dependency the user does not want.
- **Thin client with an offline cache:** still needs conflict handling when the
  cache and server diverge, so it has the complexity of local-first without its
  full offline guarantees.
- **File-sync tool only (for example Syncthing over a folder of notes):** no
  control over merges. Concurrent edits produce duplicate "conflict copies"
  instead of merged notes.
