# The `jotter` command

The command works on the same notes as the desktop app, and you can use both at once.
[Install](install.md) it first. `jotter --help` lists the commands, and
`jotter COMMAND --help` explains one.

## Notes

| To do this | Type |
|---|---|
| Add a note | `jotter add "buy milk"` |
| Add one with a title and tags | `jotter add --title "Groceries" --tag shopping "- [ ] eggs"` |
| Add what another program prints | `date \| jotter add -` |
| Add text that starts with `--` | `jotter add -- --not-an-option` |
| List your notes | `jotter list` |
| List the archive, or the trash | `jotter list --archived`, `jotter list --trash` |
| List notes with a tag | `jotter list --tag shopping` |
| Search | `jotter search eggs` |
| List your tags and how many notes have each | `jotter tags` |
| Show one note | `jotter show ID` |

`add` prints the new note's id. `list` prints one line per note: the id, when it last
changed, `pinned` or `-`, its tags, and its title or first line. Times are in UTC.

`search` looks only in active notes, not the archive or the trash.

**Ids.** `show` takes a whole id, or enough of its start that only one note matches.
Notes made at about the same time share their first characters, so copy the whole id from
`jotter list` if Jotter says `id ... is ambiguous`.

The command cannot edit, pin, archive or delete notes: use the app for that.

## Other commands

| To do this | See |
|---|---|
| `jotter import FOLDER` | [Import from Google Keep](import-export.md#import-from-google-keep) |
| `jotter export FOLDER` | [Export to Markdown](import-export.md#export-to-markdown) |
| `jotter backends ...` and `jotter sync` | [Sync](sync.md) |
| `jotter conflicts` | [Conflicts](sync-status.md#conflicts) |
| `jotter debug-log` | [The debug log](debug-log.md) |

## Another notes file

`jotter --db FILE COMMAND` uses another notes file, such as a backup:
`jotter --db ~/Backups/jotter-2026-11-04.db list`. Setting `JOTTER_DB` to a file does the
same for every command, and for the desktop app.

## When it fails

A command that fails prints one line starting with `jotter:` and ends with status 1. It
says what happened and what to do. [When something goes wrong](troubleshooting.md) lists
the messages.
