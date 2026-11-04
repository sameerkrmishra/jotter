# 0021. Local logging: a small logger of our own, a rotating file, and nothing private in it

- **Status:** Proposed
- **Date:** 2026-11-04

## Context

PLAN.md promises no telemetry but local debug logs that the user can export
(§1), and plans `core/logging` as "a thin wrapper over Kermit + rotating file
sink" (§3.2). Until M6 nothing was logged: an unexpected failure went to stderr
as a stack trace, which an app started from a menu throws away, and Ktor's
SLF4J calls printed three "no providers" warnings on every CLI run that
contacted a server (docs/milestones/m4.md).

The forces:

- **Native.** `core/sync` and the backends must log, and they build for
  `linuxX64` ([ADR-0006](0006-native-compatible-shared-modules.md)), so the
  logger and its file sink must too.
- **Privacy.** A log is attached to a public issue. It must never hold a
  note's text, title or tags, a password, a token, an `Authorization` header,
  a URL with credentials, or a file's contents.
- **Bounded.** A phone has little space and a log is never cleaned up by hand.
- **Third-party logs.** Ktor logs through SLF4J on the JVM and Android, OkHttp
  (the Android hub client, [ADR-0018](0018-android-app.md)) through
  `java.util.logging`.

## Decision

**Our own small logger, not Kermit.** `core:logging` (a `jotter.kmp-library`)
has a `Logger(tag)` with `debug`, `info`, `warn` and `error`, whose messages are
lambdas built only when their level is on, and one process-wide `Logging` that
the composition root installs with a `LogSink` and a level. Nothing is written
until it does, so tests and libraries log nowhere by default. A global is an
exception to [ADR-0005](0005-manual-dependency-injection.md): passing a logger
through every constructor down to a backend's HTTP exchange would change most
signatures in the project for something that has no behaviour to replace in a
test. Kermit would add a dependency (and an F-Droid audit entry) for what is
here about 150 lines; it has no file sink, so that would be ours anyway, and its
platform writers (Logcat, `NSLog`) are not wanted: the log file is the one place.

**What may be logged.** Ids (backend, device, note, revision), kinds, counts,
sizes, outcomes, durations, HTTP methods and status codes, blob paths (device
ids and content hashes), file paths of databases and folders, host names and
ports. Never what the Context lists. Each call site is written to this rule; it
is the first line of defence.

**Places, in logs and the debug log.** The home directory prefix is written as
`~`, so the user's name in it stays out. A WebDAV or hub URL is logged as its
scheme, host and port only: never its path, which often holds the user name (as
Nextcloud's `/remote.php/dav/files/me/` does), its user info or its query. The
WebDAV client logs each exchange by method, origin and status; the hub client by
method, the last part of the endpoint (`ping`, `list`, `blob`) and the hub's
address. A folder outside the home directory keeps its path.

**Redaction, the second line.** Every message passes `redact`, which cuts every
URL down to scheme, host and port, writes the home directory as `~`, and
replaces `Authorization` values, `Bearer`/`Basic` credentials and values named
password, token, secret or API key: a quoted value whole, spaces included, an
unquoted one with the rest of its line, as nothing says where it ends. The debug
log is redacted the same way as a whole. A pattern cannot recognise a note's
text, so exceptions get a rule of their own: a failure is logged as its class,
its causes and their first stack frames, and its message **only** for classes
whose messages say where, not what: by package `java.net`, `java.io`,
`java.nio.file`, `okio` and Ktor's socket errors, and by name `SQLiteException`
and those of Jotter's own exceptions whose code writes the whole message from
ids, numbers and paths. Not a whole Jotter package: an exception that wraps
another, such as `BlobStoreFailureException`, may repeat its cause's message in
its own, and that cause is logged by its own rule instead. A JSON parser's
message, which quotes the input it failed on, or a `require` that names a tag,
is never logged. Messages are put on one line and cut at 1,000 characters.

**The file.** `RotatingFileSink` appends each line to `<name>.log` in one write
in append mode. At 256 KiB it becomes `<name>.1.log` and so on; four files are
kept, so a log is at most about 1 MiB. Several processes may share a name, as
CLI commands that run at once share `cli.log`: each write, the size check and
any rotation happen under an exclusive POSIX record lock on `<name>.lock`
(`FileChannel.lock()` on the JVM and Android, `lockf` on Linux, which see each
other's locks), and each line carries its process id after the time. The file is
opened and closed for each line, as another process may have rotated it. The
directory is created 0700 and the files 0600; existing ones of the user's with
a wider mode are narrowed. The desktop app and the CLI still use names of their
own, so that each log reads as one app's:

| App | Directory | Name |
|---|---|---|
| Desktop (and its hub) | `$XDG_STATE_HOME/jotter/logs` (`~/.local/state/jotter/logs`) | `desktop` |
| CLI | the same | `cli` |
| Android | `noBackupFilesDir/logs`, never backed up | `android` |

The level is INFO. On Linux `JOTTER_LOG_LEVEL=debug` (or `warn`, `error`) changes
it for one run.

**Third-party logs.** `core:logging` is the SLF4J 2 provider of the apps
(`JotterSlf4jProvider`, through `META-INF/services`), so Ktor's lines reach the
log at their level and the "no providers" warnings are gone. TRACE is dropped,
because Ktor puts each request's URL and headers there. `JavaLogging.route()`
attaches a handler to OkHttp's `java.util.logging` loggers. Both pass through
`redact` like any line.

**Export, never upload.** The apps assemble a debug log (recent log lines,
versions, platform, backend kinds and states, the sync issues) and show it in
full before the user saves or shares it. Nothing is sent anywhere by Jotter.

## Consequences

### Positive

- A failure on a user's machine leaves a trace that they can read and attach.
- One rule, tested once (`RedactionTest`) and end to end through real sync
  paths with marker strings (`LogRedactionTest` in `app:shared-ui`).
- No new dependency but `slf4j-api`, which Ktor already brings.

### Negative

- A global logger: a test that installs a sink must uninstall it, and two tests
  that do so in one JVM must not run in parallel (Gradle runs a module's test
  classes one at a time).
- An exception message outside the allow-list is lost even when it was
  harmless; the stack frames usually tell where.
- Redaction is pattern-based: a credential in an unexpected shape could pass.
  That is why it is the second line, not the first.
- Opening the file and taking the lock for every line costs a few system calls a
  line; logs are written at INFO, a few lines per command or sync.
- A URL without its path is less telling: which collection failed is left to
  the backend's id.

## Alternatives considered

- **Kermit, as PLAN.md planned:** see above; its file writer would still be ours.
- **A logger passed by constructor (ADR-0005):** consistent, but it touches
  nearly every class for no testable behaviour.
- **slf4j-nop or slf4j-simple to silence SLF4J:** NOP drops Ktor's lines; simple
  prints them on stderr, unredacted.
- **Redacting note text by matching it against the database:** costly on every
  line, and incomplete for text that was deleted or never stored.
