# 0008. Sharing notes between people: shared spaces as separate stores

- **Status:** Proposed
- **Date:** 2026-11-04

**Not built in v1.** v1 has one user and no login ([PLAN.md §1](../PLAN.md#1-requirements-summary)).
This record decides the direction for sharing notes between people, such as a family's
shopping list, says what v1's design makes hard, and which v1 decisions must stay so that
the door stays open. No code changes with it.

## Context

Everything in v1 assumes that every device belongs to one person who trusts all of them:

- **One store, all notes.** A device pushes every revision it holds to every backend
  (`Pusher` takes all of `unpushed(backendId)`), and relays between backends. There is no
  way to send some notes to one place and not to another.
- **No people in the data.** A revision carries a device id, not a person. Device ids are
  not authenticated: any writer can put any id in a revision. `device.json`, the only name
  for a device, is informative, unsigned, and never read by sync.
- **Trust in the code of every writer.** [ADR-0014](0014-sync-failure-handling.md) accepts
  that a received automatic merge is not derived again: a merge with wrong text, a valid
  hash and a lower id than the honest one wins, and nothing notices. That is sound only
  because "a user's own devices run the same code".
- **Backends grant everything.** A WebDAV account, a folder and a hub token
  ([ADR-0015](0015-pc-hub-peer-protocol.md)) each allow reading and writing every path; the
  hub does not check that a device writes only to its own folder.
- **Every field is shared.** Pin and archive are last-writer-wins fields of the note
  ([ADR-0003](0003-revision-dag-and-three-way-merge.md)): one person's pin would pin it
  for everyone.
- **Clocks are trusted.** Last-writer-wins compares HLC stamps, and a revision more than a
  day ahead is stored and counts; only the receiving device's own clock is protected
  ([ADR-0003](0003-revision-dag-and-three-way-merge.md), refinements).

A dumb backend cannot enforce who may read what ([ADR-0001](0001-local-first-architecture.md),
[ADR-0004](0004-immutable-change-segments.md)). Whatever a backend holds, everyone with
access to it can read. So the unit of sharing has to be the unit of storage, and the only
access control that works on a dumb backend is a key
([ADR-0007](0007-end-to-end-encryption-plan.md)).

## Decision

**A space is a store.** A *space* is a set of notes with its own backends and its own store
key (ADR-0007). Each person has a private space, which is today's store. A shared space,
"Family", is another store, at another backend location (a shared WebDAV folder, a folder on
the hub), whose key its members hold. Every note belongs to exactly one space for its whole
life. Being a member means holding the space's key and access to one of its backends.
Nothing else grants access, and nothing less is possible on a dumb backend.

**A new member gets the space key wrapped to them.** Each person also has an X25519 key pair
for this, as Ed25519 can sign but not encrypt. A member adds a person by wrapping the space
key to that person's X25519 public key, in a key file in the space's store named by the
person, and signing it with their Ed25519 key. The public keys are exchanged in person, as a
QR code or a code compared on both screens, never trusted from a backend. We chose this over
a shared passphrase per space: a passphrase has to be passed around by some other channel,
can be guessed offline from its key file, and after removing someone a new one would have to
reach every remaining member. Wrapped keys need no shared secret, and a rotation simply
wraps the new key to the remaining members, so their devices pick it up without anyone
typing anything.

**No accounts and no login.** A *person* is a member key pair (Ed25519), made on their first
device and copied to their others with ADR-0007's recovery-code transfer, kept in the
`SecretStore` ([ADR-0016](0016-where-secrets-live.md), [ADR-0018](0018-android-app.md)).
Each device database belongs to one person. A family tablet uses the operating system's
user profiles, not a login screen in Jotter. A person is shown by a name they choose, signed
with their key; names are not unique and are not identity.

**The model.**

- Every revision names its space, and `note` and `revision` get a `space_id` column. A
  note's space is the one its root revision names.
- **A revision counts only in its note's space, signed by a member of it** (see Authorship).
  This is judged from the revision and its ancestors, as
  [ADR-0014](0014-sync-failure-handling.md) requires, so devices agree. A segment that holds
  a revision of another space is refused, like a segment in the wrong device folder today.
  Without this, a member of "Family" could write a revision with the id of someone's
  private note into the shared store and edit it.
- **Personal fields leave the shared note.** Title, body and tags stay shared and merge as
  today. Today archive and trash are one last-writer-wins field, `state` (`ACTIVE`,
  `ARCHIVED`, `TRASHED`). It splits: a shared `trashed` flag, last writer wins as now, and a
  per-person `archived` flag. Pin and archive become per person: a small record per (space,
  note), synced in that person's private space only. This changes the snapshot, so it is a
  new sync format.
- **Sharing an existing note copies it.** It becomes a new note in the space, with a new id
  and one root revision. Its private history is not revealed. The user then keeps or trashes
  the original.
- Tags are note content, so a shared note's tags are seen by every member and merge as
  today. The tag list shows the tags of all spaces, marked by space.

**Sync.**

- A backend belongs to one space. `Pusher` pushes, and relays, only that space's revisions.
- Everything else is as today: per-device folders, content-named immutable files, the same
  cycle, the same merge rules ([ADR-0011](0011-touching-changes-and-merge-format.md),
  [ADR-0013](0013-merge-base-and-covered-heads.md)).
- **Authorship.** Each user revision is signed by its author's device key, which the
  person's member key certifies (a small signed record in the space's store). A revision is
  signed, not its segment, because relaying puts one person's revisions into another's
  segments. The signature covers the revision's bytes and its space. A user revision
  without a valid signature from a member of its space is invalid, in private spaces too,
  so that nobody can pass off an unsigned revision as someone's private one.
- **Membership** is a DAG of signed records in the space's store, like revisions: each names
  the records its signer had seen. The creator signs the first, and any member can add a
  person. A removal is a record too, and names the removed person's revisions it still
  accepts, by their latest ids that the remover held. Their revisions that are not among
  those ancestors are invalid, and so are records they signed that the removal does not
  have among its ancestors, except removal records concurrent with it (neither one an
  ancestor of the other), which stand. That needs no clock, so every device judges it
  alike.
- **What a removal takes with it.** Under ADR-0014 a revision built on an invalid one is
  invalid. So a removal also invalidates every honest member's edit built on a revision of the
  removed person that the remover had not seen. Nothing of it is lost: ADR-0014 already lists
  every invalid revision with its text (`ConflictResolver.invalid`), as set-aside text. A
  device also offers its own person's set-aside edits to apply again, as a new user revision
  on the head the note now shows, and others' to save as a note.
- **When removal records disagree**, for example two members remove the same person, each
  having seen different revisions of theirs: a revision of the removed person counts if any
  removal of them accepts it. Taking the union loses the fewest honest edits built on them,
  and costs nothing in safety, as each accepted revision was seen by a member who chose to
  accept it. **Two members who remove each other at the same time are both removed.**
  Neither record is an ancestor of the other, so by the exception above each stands, and
  each voids what its target signed that it does not have among its ancestors, except the
  other removal record. Honest edits built on what is voided stay visible as set-aside
  text, as above. Whether two records are concurrent is read from the DAG alone, so every
  device computes the same members. If
  nobody is left, the space can no longer change; its notes stay readable, and anyone who
  held its key can start a new space from them.
- **Merges are derived again.** In a shared space a received automatic merge counts only if
  the receiver, from the merge's two parents and their ancestors, derives the same text,
  title, tags, last-writer-wins values and stamps, and the same losing parent, and so the
  same id. Every byte that ADR-0013 hashes for a merge comes from its parents: its timestamp
  and device id are those of the higher-stamped parent, not the device that made it
  (ADR-0003), so the comparison is exact. This is the alternative ADR-0014 rejected for one
  person; with several people, a forged merge is an attack, not a bug. It costs a merge per
  received merge.

**Conflicts.** The same rules, across people. The conflict screen shows who wrote each side,
from the signatures. Two people resolving one conflict differently make two heads, which
merge as usual. Delete against edit still brings the note back in the trash, so one member
cannot make another's edit vanish. History is never rewritten, so every earlier text stays
recoverable by everyone who had it.

**Backends.**

- *WebDAV:* a folder shared between accounts, such as a Nextcloud share. The server cannot
  stop a member from deleting or adding files anywhere in it. Added files do not count
  unless signed; deletions are repaired by ADR-0014's re-push, as long as any member's
  device holds the revisions.
- *Local folder:* a shared drive works as for one person.
- *PC hub:* it serves one folder per space. A token is paired for a person's device and
  names the spaces it may use; the hub refuses writes outside that device's own folder.
  That is a path check, like the ones it already makes, and does not read content. The hub's
  owner pairs every member's device, and is trusted with the store's ciphertext, not its
  text.

**Security model.**

- A member can read, edit, trash and delete forever every note of the space, and keep a
  copy of all of it for good. Sharing is trust in the person.
- A member cannot read another space, write as another person, forge a merge that counts, or
  remove history from other members' devices.
- **Removing a member** rotates the space key (ADR-0007), withdraws their backend access and,
  on the hub, unpairs their devices. They keep what they already had.
- **A member's clock** can stamp a revision far ahead and so win last-writer-wins on trash
  against every concurrent write. No rule can refuse it: validity must not depend on a
  receiver's clock (ADR-0014), and any time the author signs is the author's claim. The harm
  is bounded: a later revision on top of it is stamped above it (ADR-0003), so the next
  member who acts on the note wins, and the signature names who did it. This is accepted.
- **The backend operator** learns what ADR-0007 lists, plus the number of devices in a
  space.

**What v1's design makes hard.**

- Pushing every note to every backend: per-space sync needs `space_id` in the storage schema,
  in `unpushed` and in relaying.
- Unauthenticated device ids and unsigned revisions: authorship is a format change and a new
  validity rule.
- Pin, and archive inside the shared `state` field, as shared fields of the snapshot:
  splitting `state` and moving pin and archive out is a format change and a migration of
  each person's existing notes.
- Accepting received merges without deriving them: changing it costs CPU on every sync of a
  shared space.
- Backends that grant all or nothing: shared WebDAV can only be guarded by keys and
  signatures, never by the server.
- One person per database: two people on one device database is not supported, now or by
  this plan.

**What v1 must keep.** These decisions make the plan possible, and a v1 change that undoes
one would close the door:

- Dumb backends and per-device folders of immutable, content-named files: several people can
  write one store without locks or overwrites (ADR-0004, ADR-0014).
- Random note, revision and device ids, and content-hashed merges: no coordination between
  people's devices, and no id that names a person.
- Validity judged from the set of revisions alone (ADR-0014): signature and space checks fit
  in as more rules of the same kind, and devices still agree.
- One format file per format and codec, and strict segment JSON: a v1 device refuses a
  space's store as `Incompatible` instead of misreading or writing to it.
- Secrets outside the database (ADR-0016): member keys and space keys go where backend
  secrets go.
- Local decisions stay local (`dismissed_invalid`, `combined_draft` in ADR-0014): what one
  person has looked at is not synced to others.
- No synced settings record or global entity besides notes: tags, the tag list and every
  preference must stay derivable per person, or a shared space would mix them.

## Consequences

### Positive

- Sharing works on the same dumb backends, the hub included, with no server that decides.
- A private note can never reach a shared store by mistake, and a member cannot reach it.
- Members cannot impersonate each other or forge merges.
- Merge rules and convergence are those of v1; the simulator extends to several people by
  adding signatures and spaces.

### Negative

- It needs ADR-0007 first: without keys there is no access control at all.
- Two format changes: personal fields, and signatures.
- Sharing a note loses its history, by design.
- Removing a member is forward-only, and costs a key rotation on every other member's
  device.
- Deriving merges again makes shared spaces slower to sync than private ones.
- A shared WebDAV folder lets any member delete files; the store heals only while someone
  still holds the revisions.
- More for people to understand: spaces, member names, recovery codes, a new passphrase per
  rotation.

## Alternatives considered

- **Per-note permissions in one store:** what people expect, but a dumb backend serves every
  file to everyone with access, so the permissions would be advice. It needs a server that
  decides, against ADR-0001.
- **Accounts on the PC hub, which enforces access:** a real server with logins; the hub
  becomes an authority and must be up for any sharing, and WebDAV and folders could not
  share at all.
- **A key per note, in one store:** finer sharing without spaces, but segments mix notes, so
  each note would need its own files and keys, and the store would show which notes change
  together.
- **Signing segments instead of revisions:** cheaper, but relaying puts one person's
  revisions into another person's segments, and the signature would then name the relayer.
- **Moving a note into a space with its history:** keeps history, but reveals every earlier
  private version, and the note's id would live in two spaces.
- **Sharing by Markdown export and import:** works today and needs nothing, but nothing
  merges back.
- **A text CRDT for shared notes:** fewer conflicts between people, but ADR-0003 rejected it
  for v1's reasons, which still hold.
