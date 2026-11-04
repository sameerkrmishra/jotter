package io.github.sameerkrmishra.jotter.core.logging

import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** How much a log line matters. A sink gets the lines at its level and above. */
public enum class LogLevel {
    /** Detail for following one sync or request, such as each HTTP exchange. */
    DEBUG,

    /** What the app did: started, opened the notes, synced a backend. */
    INFO,

    /** Something failed and the app carried on, such as a backend that cannot be reached. */
    WARN,

    /** Something failed that the user sees, such as notes that cannot be opened. */
    ERROR,
    ;

    /** Parses a level. */
    public companion object {
        /** The level named [text], in any case, or null if there is none such. */
        public fun parse(text: String?): LogLevel? =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}

/**
 * Where the log of this process goes. An app's composition root calls [install] once, at start.
 * Until then, and in tests that install nothing, every line is built at every level and dropped:
 * so the tests of each module run every log line, and one that throws is found there, not in the
 * field. Shared code only makes [Logger]s, so it never depends on where lines go
 * (docs/adr/0020-local-logging.md).
 */
public object Logging {
    private class Installed(
        val sink: LogSink?,
        val level: LogLevel,
        val clock: Clock,
    )

    private val nowhere = Installed(null, LogLevel.DEBUG, Clock.System)

    @Volatile
    private var installed = nowhere

    /** The lowest level that is built now; [LogLevel.DEBUG] while nothing is installed. */
    public val level: LogLevel get() = installed.level

    /**
     * Sends lines at [level] and above to [sink] from now on, stamped by [clock]. Replaces what was
     * installed before; the caller closes that, if it needs closing.
     */
    public fun install(
        sink: LogSink,
        level: LogLevel = LogLevel.INFO,
        clock: Clock = Clock.System,
    ) {
        installed = Installed(sink, level, clock)
    }

    /** Drops every line from now on, as before [install]. */
    public fun uninstall() {
        installed = nowhere
    }

    internal fun isEnabled(level: LogLevel): Boolean = level >= installed.level

    /**
     * Writes one line, after [redact]ing it, with [failure] as [describeFailure] gives it. A
     * message or a sink that throws loses the line and nothing else: logging must never end what it
     * describes.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun write(
        level: LogLevel,
        tag: String,
        message: () -> String,
        failure: Throwable?,
    ) {
        val current = installed
        if (level < current.level) return
        try {
            val record =
                LogRecord(
                    current.clock.now(),
                    level,
                    tag,
                    oneLine(redact(message())),
                    failure?.let(::describeFailure),
                )
            current.sink?.write(record)
        } catch (_: Exception) {
            // Nowhere left to say it.
        }
    }
}

/**
 * Writes log lines for one part of the app, named by [tag], such as `sync` or `webdav`.
 *
 * A line says what happened in words of the code: ids, kinds, counts, sizes, states and times.
 * **Never** a note's text, title or tags, a password, token or `Authorization` header, a URL with
 * credentials, or a file's contents (docs/adr/0020-local-logging.md). Messages are built only if
 * their level is on, so a disabled [debug] costs one comparison. [redact] runs on every line as a
 * second line of defence, not as the first.
 *
 * @property tag The part of the app.
 */
public class Logger(
    public val tag: String,
) {
    /** Logs [message] at [LogLevel.DEBUG], with [failure] if there is one. */
    public fun debug(
        failure: Throwable? = null,
        message: () -> String,
    ) {
        log(LogLevel.DEBUG, failure, message)
    }

    /** Logs [message] at [LogLevel.INFO], with [failure] if there is one. */
    public fun info(
        failure: Throwable? = null,
        message: () -> String,
    ) {
        log(LogLevel.INFO, failure, message)
    }

    /** Logs [message] at [LogLevel.WARN], with [failure] if there is one. */
    public fun warn(
        failure: Throwable? = null,
        message: () -> String,
    ) {
        log(LogLevel.WARN, failure, message)
    }

    /** Logs [message] at [LogLevel.ERROR], with [failure] if there is one. */
    public fun error(
        failure: Throwable? = null,
        message: () -> String,
    ) {
        log(LogLevel.ERROR, failure, message)
    }

    /** Logs [message] at [level], with [failure] if there is one. */
    public fun log(
        level: LogLevel,
        failure: Throwable?,
        message: () -> String,
    ) {
        Logging.write(level, tag, message, failure)
    }
}
