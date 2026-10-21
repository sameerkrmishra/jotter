# core:model

The domain types of Jotter: notes, tags, ids and the hybrid logical clock (see
[docs/PLAN.md](../../docs/PLAN.md) §4). So far it contains `NoteId`.

**Dependency rule:** pure Kotlin. No I/O, no platform APIs and no dependencies
on other Jotter modules. Every other module may depend on this one, so it must
stay small, stable and fully tested.
