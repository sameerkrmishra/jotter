# Import from Google Keep, export to Markdown

Both are done with the [`jotter` command](cli.md) on Linux. The apps have no buttons for
them yet. Notes you import on the PC reach your phone through [sync](sync.md).

## Import from Google Keep

1. Go to [Google Takeout](https://takeout.google.com), choose only **Keep**, and download
   the archive.
2. Unpack it. You get a folder `Takeout` with a folder `Keep` inside.
3. Import the `Keep` folder:

   ```
   jotter import ~/Downloads/Takeout/Keep
   ```

Jotter prints five counts:

```
imported	2
skipped	0
ignored	1
warnings	1
failures	1
```

- **imported**: new notes.
- **skipped**: notes you had imported before. They stay as they are.
- **ignored**: other files in the folder, such as Keep's `.html` copies and pictures.
- **warnings**: something was left out of a note. Each gets a line, such as
  `warning	Old idea.json	ATTACHMENT_DROPPED	x.png`: Jotter keeps text only, so pictures,
  drawings and colours are dropped.
- **failures**: files that could not be imported, each with a line, such as
  `failure	Broken.json	MALFORMED_JSON	Not a valid Keep note`.

If anything failed, the last line says so, and running the same import again is safe: it
skips the notes it already has and tries the rest. Notes keep their Keep dates, labels
(as tags), pins, archive and trash. Checklists become task lists.

If you give the `Takeout` folder instead of `Keep`, Jotter tells you which folder to use.

Not tested with a real Takeout: the test used Keep files written by hand in Takeout's
format.

## Export to Markdown

```
jotter export ~/Documents/notes-export
```

Jotter writes one `.md` file per note into that folder, and creates it if needed. It
prints how many, such as `exported	6`. Each file starts with the note's details, then
the text:

```
---
id: 01a1a4ef-5401-7272-9897-989d281b185e
title: "Groceries"
tags:
  - "shopping"
pinned: false
state: active
created: 2026-11-04T05:01:50.460Z
updated: 2026-11-04T05:01:50.460Z
---
- [ ] eggs
- [x] bread
```

Files are named after the title. A note without a title is `untitled.md`, then
`untitled <id>.md`.

- Active and archived notes are exported. Add `--trash` to include the trash.
- `--tag home` exports only notes with that tag: `jotter export --tag home ~/Documents/home-notes`.
- Exporting again into the same folder replaces each note's own file and never touches
  other files.
