# interop:markdown-export

Turns notes into one Markdown file each, with YAML front-matter, so they can
leave Jotter at any time ([PLAN.md](../../docs/PLAN.md) §7). The UI and
`jotter export --tag recipes ./out` both use it.

```kotlin
val notes = NoteState.entries.flatMap { store.notes.list(NoteFilter(state = it)) }
val files = exportMarkdown(notes, ExportFilter(tag = TagName.parse("recipes")))
writeExport(files, "out".toPath(), FileSystem.SYSTEM)
```

**Dependency rule:** depends on `core:model` and okio, both part of its API.
Common code only ([ADR-0006](../../docs/adr/0006-native-compatible-shared-modules.md)).
It does not depend on `core:storage`: the caller reads the notes, as above, so
the export is a pure function that is tested without a database.

## API

| Name | What it is |
|---|---|
| `exportMarkdown(notes, filter)` | Returns an `ExportedFile` per matching note, sorted by file name. Does no I/O |
| `ExportFilter` | Which of the given notes to export: a set of states and optionally a tag |
| `ExportedFile` | A file name and the file's content. Only `exportMarkdown` creates one |
| `writeExport(files, directory, fileSystem)` | Writes the files into a directory, creating it if needed. Returns a `RenamedFile` for each file that took another name |
| `RenamedFile` | The planned and the written name of such a file, for the UI or CLI to show |

## Which notes

**Every note the caller passes, in every state, unless `ExportFilter` says
otherwise.** That includes the archive and the trash: an export is the user's
way to get their data out, so it must not lose notes by default. `state:` in
the front-matter tells them apart. To leave out the trash, pass
`ExportFilter(states = setOf(NoteState.ACTIVE, NoteState.ARCHIVED))`.

`ExportFilter` is not `core:storage`'s `NoteFilter`, which selects exactly one
state for a list view. A tag compares exactly (`Soup` is not `soup`), like
everywhere else ([`TagName`](../../core/model/README.md)). Notes deleted
forever have no `Note`, so they are never exported.

## File format

```markdown
---
id: 0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f
title: "Lentil soup"
tags:
  - "Work"
  - "soup"
pinned: true
state: archived
created: 2026-01-02T03:04:05Z
updated: 2026-01-03T03:04:05.250Z
---
- red lentils
```

- `title` and every tag are always double-quoted. That is simpler than working
  out which plain scalars YAML would misread (`null`, `#x`, `a: b`, `- x`). `"`
  and `\` are backslash-escaped. Control characters, U+2028, U+2029, U+FEFF,
  U+FFFE and U+FFFF become `\uXXXX`, so a value is always on one line. A lone
  surrogate, which `core:storage` never returns, becomes U+FFFD.
- `tags` are sorted, or `tags: []` if there are none.
- `state` is `active`, `archived` or `trashed`.
- `created` is `Note.createdAt` and `updated` the physical time of
  `Note.updatedAt`, both in UTC. The HLC counter is left out.
- The body follows the closing fence directly and is not changed in any way:
  no newline is added, and a body may start with `---`. The first line that is
  exactly `---` after the opening one always ends the front-matter, because no
  line inside it can be `---`. So a body is everything after that line.

## File names

A file's name comes from the note's title, so the rules below apply in order:

1. Whitespace becomes one space. `" * / : < > ? \ |`, control characters and
   lone surrogates become `-`. Those are the characters that FAT and Android's
   shared storage refuse, and the path separators. Invisible format characters
   and noncharacters are dropped: bidirectional overrides could disguise the
   extension, and Android's storage ignores several of them when it compares
   names.
2. Spaces, dots and hyphens are trimmed from both ends. Windows drops trailing
   dots and spaces, a leading dot hides the file, and a leading hyphen reads
   as an option on a command line. This also empties `.` and `..`.
3. The name is cut at a code point to fit 143 UTF-8 bytes with `.md`, the limit
   of eCryptfs home directories. ext4, f2fs and FAT allow 255.
4. A title with nothing left becomes `untitled`.
5. A Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM0`-`COM9`,
   `LPT0`-`LPT9`, `CONIN$`, `CONOUT$`, `CLOCK$`, also before a dot as in
   `con.txt`) gets a `_` in front, because the flash drive may end up in a
   Windows PC.
6. Names that are equal ignoring case (through `uppercase().lowercase()`, so
   `straße` meets `STRASSE`) collide. Active notes come first, then archived,
   then trashed, and within a state the lowest id. The first note keeps the
   name; each other one gets its id, as in `Soup 0192e6f0-...-5e6f.md`, with
   the title cut shorter to make room. If another note's title is exactly that
   name, that note keeps it and a counter follows the id.

So a name depends only on the set of exported notes, not on their order. What
it costs: a filter changes the set, so it can change which note keeps a shared
name. Because no name has a separator and is never `.` or `..`, and only
`exportMarkdown` can create an `ExportedFile`, a file cannot land outside the
target directory.

## Writing files

`writeExport` never overwrites one note with another, and it deletes nothing.
Exporting again updates a directory but leaves the files of notes that were
renamed or no longer match.

**Staging.** Each run creates an empty directory `.jotter-export-<random>` inside
the target, on the same file system, writes every file there, and then moves
each one into place atomically. The directory is removed at the end, even after
a failure. A full disk during writing changes nothing in the target. A failure
during the moves leaves every file whole, either new or as it was, and throws an
`IOException`. Removing the directory is best effort, so a failure there does
not fail a finished export or hide an earlier failure. That, or a crash of the
process, can leave the directory behind; it is harmless and can be deleted.

**Clashes.** Common code has no Unicode normalisation tables, so rule 6 does not
see that `é` as one character and as `e` plus an accent are the same file on
Android's storage. The staging directory shows it: it starts empty, so a name
that `exists` there is a clash within this run, whatever rule the file system
compares names by. The note then takes its name with the id, and `writeExport`
reports that in a `RenamedFile`. Notes claim names in the order of rule 6, so
the same notes get the same names on every run. A clash never aborts an export.

**Files already in the target.** Each exported file starts with `---` and
`id: <note id>`, which is how a later export knows whose file it finds. A file
that starts with this note's id is replaced. Anything else under the name stays
as it is: another note's file, the user's own file, a directory, a file that
cannot be read. The note takes
its name with the id, or if that is taken as well, `<note id> 1.md` and so on.
This also holds for a note that was exported earlier and is not part of this
export, so a filter or a deleted note cannot lose a file. The price is that a
note can keep a name with an id after the note that held the plain name is gone,
until the old file is removed. A file that a user edited keeps its id, so it is
replaced like any other export of that note. A read error on the note's own
file can therefore leave an id-named duplicate of it, reported as a rename. A
failure to look up a name is not a clash: where the platform reports it, it ends
the export with an `IOException`. On the JVM and Android, okio's `metadataOrNull`
returns null on a `FileSystemException`, so a failed lookup there can read as a
free name, and the move into place then replaces whatever is under it or fails
with the `IOException`. A file system on which 1000 counted names are all taken
ends the export with an `IOException` too.

## Tests

Shared tests in `commonTest` run on the JVM (counted by the coverage gate), on
linuxX64 and on the Android host. Kotest property tests feed arbitrary titles
and tags, mixed with fragments that are known troublemakers: names stay safe
(checked by rules restated in the test), unique ignoring case and independent
of input order, and titles, tags and bodies read back equal through a strict
test-only front-matter parser. The writer's tests use a real directory, through
okio's `ForwardingFileSystem` to act like storage that normalises names and to
fail like a full disk, and cover a retry, a re-export, a later twin and a failure
partway. All data is synthetic.
