package io.github.sameerkrmishra.jotter.core.logging

import kotlin.time.Instant

/**
 * One log line, already redacted.
 *
 * @property time When it was written.
 * @property level How much it matters.
 * @property tag The part of the app that wrote it.
 * @property message What happened, on one line.
 * @property failure The exception, as [describeFailure] gives it, or null.
 */
public class LogRecord(
    public val time: Instant,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val failure: String?,
) {
    /**
     * The record as text: one line such as `2026-11-04T10:00:00.123Z INFO sync: Synced`, then the
     * failure's lines, each indented by two spaces. Ends with a line break.
     */
    public fun format(): String =
        buildString {
            append(time)
                .append(' ')
                .append(level.name)
                .append(' ')
                .append(tag)
                .append(": ")
            append(message).append('\n')
            failure?.lineSequence()?.forEach { append("  ").append(it).append('\n') }
        }
}

/** Where log lines go, such as [RotatingFileSink]. Must be safe to call from any thread. */
public fun interface LogSink {
    /** Writes [record]. May throw; [Logging] drops the line then. */
    public fun write(record: LogRecord)
}
