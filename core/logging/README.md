# core:logging

The local log of every app: a small `Logger`, a rotating log file, the reading
of that file for a debug log, and the bridges that bring Ktor's SLF4J and
OkHttp's `java.util.logging` lines into it
([ADR-0020](../../docs/adr/0020-local-logging.md)). Nothing leaves the device:
the user exports a debug log by hand.

```kotlin
// The composition root, once:
val sink = RotatingFileSink(logDirectory, "desktop")
Logging.install(sink, LogLevel.INFO)

// Anywhere:
private val log = Logger("sync")
log.info { "Synced backend ${backend.id}: sent $sent files" }
log.warn(failure) { "Backend ${backend.id} could not be reached" }
```

**Dependency rule:** depends only on okio, atomicfu and, on the JVM and
Android, `slf4j-api`. Builds for `linuxX64` (ADR-0006).

## What may be logged

Ids, kinds, counts, sizes, outcomes, durations, HTTP methods and status
codes, blob paths, the paths of databases and folders, host names and ports.

**Never** a note's text, title or tags, a password, a token, an
`Authorization` header, a URL with credentials, or a file's contents. Every
call site keeps to this; `redact` is the second line of defence, not the first.

## Design

- **`Logging`** is the one process-wide switch. Until an app calls `install`,
  nothing is written, so libraries and tests log nowhere. Messages are lambdas,
  built only when their level is on.
- **`redact`** runs on every message: URL user info, `Authorization` values,
  `Bearer`/`Basic` credentials, and values named password, token, secret or API
  key become `<redacted>`. Messages are put on one line and cut at 1,000
  characters.
- **`describeFailure`** gives an exception's class, causes and first 12 stack
  frames each. Its message is kept only for classes whose messages are known to
  say where rather than what (`java.net`, `java.io`, `java.nio.file`, Ktor's
  socket errors, `SQLiteException`, and Jotter's own); a JSON parser's message,
  which quotes its input, is not.
- **`RotatingFileSink`** appends and flushes each line. At 256 KiB, `name.log`
  becomes `name.1.log` and so on, and four files are kept: about 1 MiB at most.
  One process writes one name.
- **`LogFiles`** reads the newest part of each log for a debug log, starting at
  a whole line.
- **`JotterSlf4jProvider`** is found by SLF4J through `META-INF/services` on the
  JVM and Android. TRACE is dropped: Ktor writes URLs and headers there.
- **`JavaLogging.route()`** attaches a handler to OkHttp's loggers.

## Tests

`commonTest` runs on the JVM, Android devices and `linuxX64`: levels, the line
format, redaction, which exception messages are kept, rotation and reading.
`jvmTest` covers the SLF4J provider and the `java.util.logging` bridge. The end
to end check that no note text, title, tag, password or token reaches a log
through real sync is `LogRedactionTest` in `app:shared-ui`.
