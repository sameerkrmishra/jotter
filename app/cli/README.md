# app:cli

The `jotter` command: add, list, show, search, import, export and sync notes from a
terminal or a script, in the same database as the desktop app. It is a
[Clikt](https://ajalt.github.io/clikt/) application on the JVM. The notes
themselves are `core:storage`'s job, the file formats `interop/*`'s and the backends
`app:sync`'s; this module only parses arguments, finds files and prints.

```
./gradlew :app:cli:installDist
app/cli/build/install/jotter/bin/jotter add "buy milk"
```

`java` comes from `JAVA_HOME`, or else from `PATH`, and must be version 21 or
newer. Symlink `bin/jotter` into your `PATH` to use it from anywhere.

## Commands

| Command | What it does |
|---|---|
| `jotter add TEXT` | Adds a note and prints its id. `--title TITLE`, `--tag TAG` (repeatable). `-` as `TEXT` reads the note from stdin as UTF-8, without its trailing newlines. `TEXT` may start with `-`, see below |
| `jotter list` | Lists the active notes, pinned first, then the most recently updated. `--archived` or `--trash` lists those instead (the last one given wins), `--tag TAG` filters by a tag |
| `jotter show ID` | Prints one note in any state |
| `jotter search QUERY` | Searches the active notes, at most the best 100. QUERY is plain words, see `core:storage` |
| `jotter tags` | Lists the tags of the notes outside the trash |
| `jotter import PATH` | Imports Google Keep notes from a Takeout `Keep` directory or one `.json` file, see below |
| `jotter conflicts [ID]` | Lists what sync needs you to look at: open conflicts, edits that wait, and text set aside. With an `ID`, prints every version of that note. It only reads: answering is the desktop app's |
| `jotter sync` | Syncs once with every backend, in turn, and prints a line for each. Fails if any did not sync, see below |
| `jotter backends` | Lists the backends |
| `jotter backends add PATH` | Adds a folder, such as a USB stick, as a backend and prints its id. `--name NAME`. A folder that is not a Jotter store yet needs `--new`, see below |
| `jotter backends remove ID` | Stops syncing with a backend and forgets it on this device. Its files stay where they are |
| `jotter backends status [ID]` | Asks each backend, or one, whether it can be reached now, and prints that with its last sync and issues |
| `jotter export DIR` | Writes one Markdown file per note into `DIR`, creating it if needed. **Active and archived notes; `--trash` adds the trash.** `--tag TAG` filters by a tag: `jotter export --tag recipes ./out` |

`jotter add` takes anything that is not one of its options as the note, so
checklist items need no quoting beyond the shell's: `jotter add "- [ ] milk"`,
`jotter add "-1 degrees" --tag weather`. The one exception is text of the form
`--word` or `--word=value`, with no space before any `=`. That is refused as a
mistyped option, so `--tga x` is an error and not a note, and nothing is written.
To store such text, put `--` before it: `jotter add -- --tga`. `---`, `-- item` and
`--force is risky` are text. `-h` alone is still help, and `-` alone is stdin.

An `ID` is a whole id or a start of one that only one note, in any state, has.
Letter case and dashes do not matter. A prefix reads every note, because
`NoteService` has no lookup by prefix; a whole id reads just the one.

### Import

Each `.json` directly in the directory is read in turn, sorted by name, so only
one is in memory at a time and a repeated run fails the same one of two
duplicates. Everything else there, such as Keep's `.html`, attachments and
subdirectories, is left alone and counted as `ignored`. A bad file does not stop
the rest. A file must be UTF-8 and at most 64 MiB; otherwise it is a `READ_ERROR`
failure. What maps to what and what can go wrong is in
[`interop:keep-import`](../../interop/keep-import/README.md).

Importing again is always safe: a note that is already here, even one deleted
forever, is skipped and stays as it is, so newer Keep edits do not come in
([ADR-0009](../../docs/adr/0009-import-preserves-source-timestamps.md)). A run
that stopped or partly failed can simply be repeated.

Import 1,000 synthetic notes: median 1.1 s from process start to exit, as one transaction per note (5 runs; 0.36 s to skip them all again), on the hardware under "Startup time", with sibling builds running.

### Backends and sync

Only folders can be backends so far; WebDAV and the PC hub come later in M4. An `ID`
of a backend is a whole id or a start of one that only one backend has.

`backends add` never sets up a folder by itself. If the folder is not a Jotter store
yet, it explains what setting it up does and fails: it would create a hidden file,
`.jotter-store`, and the next sync would copy the history of all your notes into a
`jotter` folder there. On the bare mount point of an unplugged USB stick that would
put them on this computer's disk, so `--new` must say that this folder is meant
([ADR-0012](../../docs/adr/0012-local-folder-publishing.md)). A folder another device
set up is added without `--new`. A relative `PATH` is taken from the current
directory and stored as an absolute one.

`backends remove` deletes nothing in the folder: other devices can go on syncing
there. Adding it again later copies this device's whole history there again.

`sync` does in one run what the desktop app does in the background, and records the
outcome, which the app's backend screen and `backends status` show too. It may run
while the app is open: the sync engine is safe from two processes.

### Export

The format and the file names are in
[`interop:markdown-export`](../../interop/markdown-export/README.md). The trash
stays out by default, unlike in that module, because everywhere else in the CLI
it is a place to be asked for. Exporting again replaces a note's own file and
never another file; a note that has to take another name for that is reported.

## Output

Output on stdout is meant for pipes and stays the same in every locale, time
zone and terminal width. It is UTF-8, with no colours and no wrapping. Times are
UTC, to the second (`2026-11-01T15:04:27Z`). Empty output is a success: `list`
and `search` print nothing when nothing matches.

| Command | One line per note or tag, or the whole output |
|---|---|
| `add` | `ID` |
| `list` | `ID`, tab, `UPDATED`, tab, `pinned` or `-`, tab, tags joined by `,` or `-`, tab, summary |
| `search` | `ID`, tab, summary, tab, snippet |
| `tags` | `TAG`, tab, count |
| `import` | Five lines of `NAME`, tab, count: `imported`, `skipped`, `ignored`, `warnings`, `failures` |
| `export` | `exported`, tab, count |
| `conflicts` | `ID`, tab, `KIND`, tab, summary, one line per item. `KIND` is `conflict`, `waiting` (an earlier change has not arrived), `waiting-for-conflict` or `set-aside` |
| `conflicts ID` | For each version, the headers `kind:`, `version:` (`shown`, `other`, `waiting`, or for set-aside text `bad-history`, `damaged`, `collision`), `title:`, a blank line and the body, with a blank line between versions. For people, not scripts: a body can hold anything |
| `sync` | `ID`, tab, `NAME`, tab, `OUTCOME`, tab, a description for people. `OUTCOME` is `synced`, `unreachable`, `interrupted`, `incompatible`, `unsupported` or `failed` |
| `backends` | `ID`, tab, `NAME`, tab, `LOCATION`, tab, last success (a time, or `never`), tab, outcome of the last sync (as for `sync`, or `never`) |
| `backends add` | `ID` |
| `backends remove` | One sentence that says where the files stay |
| `backends status` | For each backend the headers `id:`, `name:`, `location:`, `reachable:` (`yes`, `no`), `state:` (in words), `last-success:`, `last-attempt:` (a time and an outcome, or `never`), then `detail:` and one `issue:` per issue of the last sync, if there was one; a blank line between backends. For people |
| `show` | The headers `id:`, `title:`, `tags:`, `state:` (`active`, `archived`, `trash`), `pinned:` (`yes`, `no`), `created:`, `updated:`, a blank line, and the body as it is |

The summary is the title, or else the first line of the body with text in it.
Control characters in a summary, snippet or `show`'s title become spaces, so
there is always exactly one line per note and tabs only between columns. A tag
may itself contain a comma.

On stderr, after the summary, `import` prints a line per warning and per failure,
and `export` one per file that took another name. These are tab-separated too:

```
warning<TAB>FILE<TAB>KIND<TAB>DETAIL
failure<TAB>FILE<TAB>KIND<TAB>DETAIL
renamed<TAB>PLANNED NAME<TAB>WRITTEN NAME
issue<TAB>BACKEND ID<TAB>DESCRIPTION
```

`sync` prints an `issue` line for each problem a backend reported without failing,
such as a damaged file from another device.

`KIND` is a name from `interop:keep-import`, or `READ_ERROR` for a file that could
not be read. `DETAIL` is `-` if there is none. No line quotes a note's title or
text from inside a file, but Keep names its files after the titles, so `FILE`
usually shows one, and an `INVALID_TAG` detail shows the label.

## Errors

A failure prints one line starting with `jotter: ` on stderr, nothing on stdout,
no stack trace, and exits with status 1: a missing note or an ambiguous id, an
empty note, a file that is not a Jotter database, a database from a newer version
of Jotter, a directory that cannot be created, a path that cannot be opened or is
not valid in the locale, an unreadable stdin, no usable database location, or a
database that stayed locked.
For `sync`, one or more backends that did not sync, with a last line that says how
many, after the lines of all of them; `sync` without backends; and for `backends`,
a folder that is missing, not a store without `--new`, already added, or an id that
matches no backend or several.
Clikt's usage errors also exit with status 1, and print the usage line first.

`import` is the exception, because part of a run can succeed: after a bad file or
a lock it still prints its summary and its lines, then a last `jotter: ` line that
says repeating it is safe, and exits with status 1. A path that is missing, not
readable, neither a directory nor a `.json` file, or a directory without `.json`
files fails as above, before the database is opened or created; for the top
directory of a Takeout the message names its `Keep` directory. `export` fails as above if the target cannot be
written. As `writeExport` stages its files, a failure while writing changes nothing
there, and one during the final moves leaves every file whole.

A locked database means another process kept it for the whole 5 s busy timeout.
Nothing was written, and the command may be tried again. `import` stops at the
first note that meets the lock, and keeps the notes before it.

A failure to write to stdout also exits with status 1, so `jotter list > /dev/full`
does not pass for a success. A reader that leaves, as in `jotter list | head -1`,
is not a failure: as for any Unix filter, SIGPIPE ends the process silently, with
status 141. `main()` restores that default, which the JVM turns off, through
`sun.misc.Signal`.

## Database location

The first of these that is set is used. The directory is created if needed.

1. `--db FILE`, before the command: `jotter --db notes.db list`
2. the environment variable `JOTTER_DB`
3. `$XDG_DATA_HOME/jotter/jotter.db`, if `XDG_DATA_HOME` is an absolute path
4. `~/.local/share/jotter/jotter.db`

An empty value counts as not set, for `HOME` too; without `HOME`, the home directory
comes from the user's passwd entry, and with neither the command fails. The file
must be on a local file system
(`core:sqlite`), and any command creates it if it is missing. The desktop app uses
the same file: steps 2 to 4 are `linuxDatabasePath` in `core:storage`.

## Design

- **One composition root** ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)):
  `JotterCommand` builds the subcommands and finds the database. `withNotes`
  opens it for one command and closes it, and is the one place that turns
  failures into messages. `Main.kt` holds only `main()`, excluded from the
  coverage gate in `jotter.root`.
- **Sync.** `SyncCommands.kt` builds an `app:sync` `BackendManager` for the one
  command that needs it, with `NoSecretStore`, as folders need no secrets
  ([ADR-0015](../../docs/adr/0015-where-secrets-live.md)), and runs it on the
  command's own thread with `runBlocking`. `jotter add` loads none of it.
- **`clikt-core`, not `clikt`.** The usual `CliktCommand` prints through the Mordant
  terminal library, which expands tabs and so would break the columns above, and
  brings JNA onto the classpath. `CoreCliktCommand` prints plain text. But by
  default it reads no environment variables, prints errors on stdout and exits
  with status 0 after a failure, so `main()` sets all three. Clikt's test support
  replaces those same settings and hides any mistake in them, so `MainTest` checks
  them in a real process, with a temporary `HOME`.
- **Tests.** `CliTest` runs the commands through Clikt's `test` against a
  temporary database, with the real `JotterCommand`. That helper needs the `clikt`
  artifact, which is why it is a test dependency, and it records through Mordant,
  so `CliTest` captures the text itself. The tests for a locked database wait out
  the real timeout, 10 s together. `MainTest` and `StartScriptTest` run real
  processes with a temporary `HOME`; `test` depends on `installDist` for the latter.
  `InteropEndToEndTest` takes 1,000 synthetic notes through `import`, again `import`
  and `export` in real processes. `core:storage` has no limit on `list`, and that
  test would show one: the only page size is `search`'s 100.
- **SQLite's exception.** `NoteStore.open` reports a lock as `StorageBusyException`
  but lets every other driver failure through, such as a file that is not a
  database, so this module still depends on `androidx.sqlite` for that one type.
- **Arguments outside UTF-8 locales.** The JVM decodes arguments and environment
  variables with the locale's charset, which the start script cannot change. Under
  `LC_ALL=C` non-ASCII text in an argument is lost, and such a path fails with
  `not a valid path`. Stdin and output are always UTF-8.

## Startup time

[ADR-0006](../../docs/adr/0006-native-compatible-shared-modules.md) sets a target:
`jotter add` in about 200 ms. **Met: the median is 152 to 168 ms on JDK 21.**

The start script is `src/main/scripts/start-script.template`, not Gradle's, and
holds all the JVM flags. The saving comes from an application class data archive
in `$XDG_CACHE_HOME/jotter/` (`~/.cache/jotter/` by default, about 6 MB), one file
per JDK. `-XX:TieredStopAtLevel=1` and `-XX:+UseSerialGC` alone gain little, as the
table shows; they stay because the archive is made with them. `-XX:-UsePerfData`
skips a memory-mapped file in `/tmp`. `clikt-core` keeps Mordant's classes out of
startup as well.

**The cache affects only speed.** A command only ever reads the archive. Its exit
status and output cannot depend on the cache, because a failed `add` that had in
fact stored its note would be retried into a duplicate. If the archive is missing,
empty, not an archive, or older than a jar or the JDK, the command runs without it
and a detached, silenced process makes a new one: a real `add` on a throwaway
database, into a temporary file that is then moved into place. A lock directory
keeps parallel runs to one such process. So the first run takes about 320 ms, with
creating the database, and the next ones are fast about a second later. Without a cache (no `HOME`, a read-only
directory) every run takes the slower path. `StartScriptTest` covers this through
the real script.

A corrupted archive that still has the right header can crash the JVM, which does
not check its contents. Deleting `~/.cache/jotter/` is always safe.

Method: the median wall-clock time of 10 runs of the installed distribution,
`jotter add "buy milk"`, from starting the process to its exit with stdout to
`/dev/null`, timed from Python with `perf_counter`. Each row used a database and a
temporary `HOME` of its own, on the local disk: one run to create them, a wait for
the archive, one discarded run, then the 10. So the database held 2 to 11 short
notes. Each row waited until under 0.6 of 12 cores were busy. "Gradle script" is
Gradle's default script with the flags in `JAVA_OPTS`.

Hardware: Intel Xeon W-2135 @ 3.70 GHz (6 cores, 12 threads), 64 GB RAM, NVMe SSD
with btrfs, EndeavourOS, Linux 6.18. JDK 21 is Temurin 21.0.12.1, the build's
toolchain; JDK 27 is OpenJDK 27.0.1, this machine's `java`. Measured 2026-11-01.

The final build, in three rounds:

| Start script | JDK | Round 1 | Round 2 | Round 3 |
|---|---|---:|---:|---:|
| **As shipped** | **21** | **152 ms** | **168 ms** | **159 ms** |
| **As shipped** | **27** | **176 ms** | **175 ms** | **160 ms** |
| As shipped, read-only cache, so no archive | 21 | 277 ms | 258 ms | 253 ms |

An earlier build, the same day, shows what each flag contributes. It differs from
the final one in that its script made the archive during the command itself, so
its last row is 155 to 162 ms:

| Start script and flags | JDK | Median, round 1 | Median, round 2 |
|---|---|---:|---:|
| Gradle script, no flags | 21 | 274 ms | 270 ms |
| Gradle script, no flags | 27 | 271 ms | 298 ms |
| Gradle script, `-Xshare:off` (no CDS at all) | 21 | 314 ms | 316 ms |
| Gradle script, `-XX:TieredStopAtLevel=1` | 21 | 262 ms | 262 ms |
| Gradle script, `-XX:+UseSerialGC` | 21 | 263 ms | 263 ms |
| Gradle script, both | 21 | 270 ms | 257 ms |
| Tuned script, `-Xshare:off` (all flags but no CDS) | 21 | 301 ms | 295 ms |
| Tuned script, with the archive | 21 | 155 ms | 158 ms |
| Tuned script, with the archive | 27 | 162 ms | 157 ms |

Measured again on 2026-11-03 after M4 added `jotter sync` and `jotter backends`, with
the same method and machine, under 0.25 of 12 cores busy: **138 and 151 ms on JDK 21**
(Temurin 21.0.12.1) and 154, 151 and 153 ms on JDK 27 (OpenJDK 27.0.1), in rounds of 10.
`jotter add` loads none of the sync code.

The JVM is therefore fast enough, and ADR-0006's native CLI is not needed for now.
Under load the numbers are far worse: one run with sibling builds on the machine
took about 1200 ms, without tuning. Measure on a quiet machine.
