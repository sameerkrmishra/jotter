# Architecture decision records

An architecture decision record (ADR) is a short document that captures one
significant design decision: the problem, the choice made, and what it costs.
ADRs let a newcomer understand why Jotter is built the way it is without
digging through old discussions. They also make it obvious when a later change
contradicts an earlier decision.

## Status lifecycle

Each ADR has one status:

- **Proposed**: written and under review. Not yet binding.
- **Accepted**: agreed. Code and reviews follow it.
- **Accepted, amended by NNNN**: still binding. Read it together with the later
  ADR, which takes precedence where the two differ.
- **Superseded by NNNN**: replaced by a later ADR. The old record stays in
  place, unchanged except for its status line, so the history is kept.

An accepted ADR is not edited to change its decision. To change a decision,
write a new ADR and mark the old one as amended or superseded.

## Adding an ADR

1. Copy [`0000-template.md`](0000-template.md) to `NNNN-short-title.md`, using
   the next free number.
2. Fill in every section. Keep it short and concrete. State the downsides.
3. Set the status to Proposed and add a row to the index below.
4. Open a pull request. The ADR is reviewed like code (see
   [CONTRIBUTING.md](../../CONTRIBUTING.md)). On approval, set the status to
   Accepted.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-local-first-architecture.md) | Local-first architecture | Accepted |
| [0002](0002-sqlite-via-sqldelight.md) | SQLite via SQLDelight with bundled SQLite | Accepted |
| [0003](0003-revision-dag-and-three-way-merge.md) | Revision DAG with three-way merge | Accepted, amended by 0009, 0011, 0013 |
| [0004](0004-immutable-change-segments.md) | Immutable change segments on dumb blob stores | Accepted, amended by 0014 |
| [0005](0005-manual-dependency-injection.md) | Manual constructor injection | Accepted |
| [0006](0006-native-compatible-shared-modules.md) | Shared modules compile and test on Kotlin/Native | Accepted, amended by 0010, 0015 |
| [0009](0009-import-preserves-source-timestamps.md) | Imported notes keep their source timestamps as revision stamps | Accepted |
| [0010](0010-compose-ui-outside-linuxx64-rule.md) | Compose UI stays outside the linuxX64 rule | Accepted |
| [0011](0011-touching-changes-and-merge-format.md) | Touching changes merge, and the merge rules are part of the sync format | Accepted, amended by 0013 |
| [0012](0012-local-folder-publishing.md) | Publishing files in a local folder, and telling a store from a mount point | Proposed |
| [0013](0013-merge-base-and-covered-heads.md) | The merge base, covered heads and the canonical form of a merge | Proposed |
| [0014](0014-sync-failure-handling.md) | Failures in the sync cycle: validity, content names, repairs, restored backups and limits | Proposed |
| [0015](0015-pc-hub-peer-protocol.md) | The PC as hub: protocol, security, how it runs and where it stores | Proposed |

ADR-0007 (encryption plan) and ADR-0008 (multi-user plan) are planned for
milestone M6.
