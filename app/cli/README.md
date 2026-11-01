# app:cli

The `jotter` command: add, list, show and search notes from a terminal or a
script, in the same database as the desktop app. It is a
[Clikt](https://ajalt.github.io/clikt/) application on the JVM. The notes
themselves are `core:storage`'s job; this module only parses arguments and
prints.

```
./gradlew :app:cli:installDist
app/cli/build/install/jotter/bin/jotter add "buy milk"
```

`java` comes from `JAVA_HOME`, or else from `PATH`, and must be version 21 or
newer. Symlink `bin/jotter` into your `PATH` to use it from anywhere.

## Commands

| Command | What it does |
|---|---|
| `jotter add TEXT` | Adds a note and prints its id. `--title TITLE`, `--tag TAG` (repeatable). `-` as `TEXT` reads the note from stdin as UTF-8, without its trailing newlines |
| `jotter list` | Lists the active notes, pinned first, then the most recently updated. `--archived` or `--trash` lists those instead (the last one given wins), `--tag TAG` filters by a tag |
| `jotter show ID` | Prints one note in any state |
| `jotter search QUERY` | Searches the active notes, at most the best 100. QUERY is plain words, see `core:storage` |
| `jotter tags` | Lists the tags of the notes outside the trash |

An `ID` is a whole id or a start of one that only one note, in any state, has.
Letter case and dashes do not matter. A prefix reads every note, because
`NoteService` has no lookup by prefix; a whole id reads just the one.

`import` and `export` come with `interop/*`.

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
| `show` | The headers `id:`, `title:`, `tags:`, `state:` (`active`, `archived`, `trash`), `pinned:` (`yes`, `no`), `created:`, `updated:`, a blank line, and the body as it is |

The summary is the title, or else the first line of the body with text in it.
Control characters in a summary, snippet or `show`'s title become spaces, so
there is always exactly one line per note and tabs only between columns. A tag
may itself contain a comma.

## Errors

A failure prints one line starting with `jotter: ` on stderr, nothing on stdout,
no stack trace, and exits with status 1: a missing note or an ambiguous id, an
empty note, a file that is not a Jotter database, a database from a newer version
of Jotter, a directory that cannot be created, a path that cannot be opened or is
not valid in the locale, an unreadable stdin, no usable database location, or a
database that stayed locked.
Clikt's usage errors also exit with status 1, and print the usage line first.

A locked database means another process kept it for the whole 5 s busy timeout.
Nothing was written, and the command may be tried again.

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

The JVM is therefore fast enough, and ADR-0006's native CLI is not needed for now.
Under load the numbers are far worse: one run with sibling builds on the machine
took about 1200 ms, without tuning. Measure on a quiet machine.
