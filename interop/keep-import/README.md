# interop:keep-import

Imports Google Keep notes from a Google Takeout into `core:storage`, keeping
their times and without duplicating anything on a repeated run
([PLAN.md](../../docs/PLAN.md) §7,
[ADR-0009](../../docs/adr/0009-import-preserves-source-timestamps.md)).

The input is the text of `Keep/*.json` files. Finding and reading the files is
the caller's job, so this module does no file I/O and the CLI supplies them.

```kotlin
val documents = files.sortedBy { it.name }.asSequence()
    .map { KeepDocument(it.name, it.readText()) }
val report = KeepImporter(store.notes).import(documents)
report.failures.forEach { println("${it.source}: ${it.kind} ${it.detail}") }
```

**Dependency rule:** depends on `core:storage` (part of its API),
kotlinx.serialization and okio. Both libraries publish linuxX64 variants
([ADR-0006](../../docs/adr/0006-native-compatible-shared-modules.md)).

## API

| Type | What it is |
|---|---|
| `KeepImporter` | `import(documents)`: imports a sequence of documents and returns a report |
| `KeepDocument` | One file: a name for the report, and its text |
| `KeepImportReport` | `imported`, `skipped`, `warnings`, `failures`, and whether the run `stopped` early |
| `KeepNoteRef` | A document's name and its note's id |
| `KeepImportWarning` | Something left out of an imported note |
| `KeepImportFailure` | A document that was not imported, and why |

## Mapping

| Keep | Jotter |
|---|---|
| `title` | Title, possibly empty |
| `textContent` | Body, as it is. Keep's text is not escaped, so Markdown characters in it take effect |
| `listContent` | Body: a task list of `- [ ] text` and `- [x] text`, after `textContent` and a blank line if a note has both. Further lines of an item are indented by two spaces |
| `annotations` of source `WEBLINK` | Body: one line each after the text, unless the text already contains the whole URL, followed by the end of the text or a character no URL can contain. `[title](url)` if the title is not empty, differs from the URL, and neither has a character that breaks a Markdown link; otherwise the bare URL |
| `labels[].name` | Tags, through `TagName` |
| `sharees` | Dropped, with a warning that has their number only. No field of a sharee is read, so no email address reaches the report |
| `isPinned` | Pin |
| `isTrashed`, `isArchived` | State; trashed wins over archived |
| `createdTimestampUsec` | Time of the root revision, and the source of the note id |
| `userEditedTimestampUsec` | Time of a second revision, if later in whole milliseconds |
| `attachments` | Dropped, with a warning: v1 has no attachments |
| `color`, other kinds of `annotations` and all other fields | Ignored |

A `null` counts as a missing field.

## Note ids

The id is a UUIDv5 (RFC 9562) in the URL namespace, of the name
`urn:jotter:google-keep:<createdTimestampUsec>`. The prefix keeps ids of a later
importer apart from Keep's. `createdTimestampUsec` is the one field that does
not change between Takeouts, so two devices and two Takeouts find one note. A
test pins the result: changing the scheme would import every note again as a
new one.

## What goes wrong, and what happens

| Case | Result |
|---|---|
| Not JSON, not an object, or a field of the wrong type | Failure `MALFORMED_JSON` with a fixed detail: the parser's message quotes the file, and a report must not repeat a note's content |
| `createdTimestampUsec` missing or not positive | Failure `MISSING_CREATED_TIME`: there is no stable id, and an id from anything else would import the note again from the next Takeout |
| Same id as an earlier document of this run | Failure `DUPLICATE_ID` for the later one, naming the first |
| `userEditedTimestampUsec` missing or not positive | Imported at the created time, warning `MISSING_EDITED_TIME` |
| Edited not later than created | Imported with one revision, no warning (ADR-0009) |
| A label `TagName` rejects | Imported without that tag, warning `INVALID_TAG` |
| An attachment | Imported without it, warning `ATTACHMENT_DROPPED` |
| A title or body with characters SQLite cannot store, such as a lone surrogate | Imported with U+FFFD in their place, warning `TEXT_REPLACED` naming `title` or `body` |
| Sharees | Imported as a private note, warning `SHARING_DROPPED` with their number |
| The note has a history here, even a tombstone | Skipped, with no warnings: nothing was imported |
| The database cannot store the note, such as tens of thousands of labels | Failure `STORAGE_ERROR` with a fixed detail, as storage messages can quote a note; its transaction rolls back and the run goes on |
| The database stays locked | Failure `STORAGE_BUSY`, and the run stops |

Each note is its own transaction. Only a database that stays locked ends a run
early, since every later note would wait for the same lock. The report then has
`stopped` set and lists everything up to that point, which matters because a
later run skips those notes and does not report their warnings again.

A failed `DUPLICATE_ID` depends on order: pass documents in a stable order so
that a repeated run fails the same one. Two Keep notes created in the same
microsecond should not occur outside hand-edited files.

## DTOs

The `dto` package holds the `@Serializable` classes of the Takeout format, and
plain data only: the coverage gate leaves it out for the code the serialization
plugin generates, so any logic belongs outside it.

## Tests

`commonTest` runs on the JVM (counted by the coverage gate) and on linuxX64,
against a real `NoteStore` and synthetic documents only, never real user data.
Android host tests are disabled, as in `core:storage`.
