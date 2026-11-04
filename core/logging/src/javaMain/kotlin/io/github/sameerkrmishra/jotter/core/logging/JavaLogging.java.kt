package io.github.sameerkrmishra.jotter.core.logging

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter
import java.util.logging.Logger as JavaLogger

/**
 * The `java.util.logging` loggers of OkHttp, which the Android app's hub client uses (ADR-0018).
 * OkHttp's Android platform gives the first three their own handler and cuts them off from their
 * parents, so each is named.
 */
public val OKHTTP_LOGGERS: List<String> =
    listOf("okhttp3.OkHttpClient", "okhttp3.internal.http2.Http2", "okhttp3.TaskRunner", "okhttp3")

/** Routes `java.util.logging`, which OkHttp logs through, to [Logging]. */
public object JavaLogging {
    // java.util.logging keeps loggers only weakly: one collected would lose its handler.
    private val routed = mutableListOf<JavaLogger>()

    /**
     * Sends what the loggers [names] and their children log at INFO and above (FINE and above at
     * [LogLevel.DEBUG]) to [Logging], under their last name, and no longer to their parents'
     * handlers, such as the console's. Routing a logger again changes nothing.
     */
    public fun route(names: List<String> = OKHTTP_LOGGERS) {
        synchronized(routed) {
            names.forEach { name ->
                val logger = JavaLogger.getLogger(name)
                if (logger.handlers.none { it === Bridge }) logger.addHandler(Bridge)
                logger.useParentHandlers = false
                logger.level = if (Logging.isEnabled(LogLevel.DEBUG)) Level.FINE else Level.INFO
                if (routed.none { it === logger }) routed += logger
            }
        }
    }

    private object Bridge : Handler() {
        private val formatter = SimpleFormatter()

        override fun publish(record: LogRecord) {
            val level = levelOf(record.level)
            Logger(record.loggerName.orEmpty().substringAfterLast('.')).log(level, record.thrown) {
                formatter.formatMessage(record).orEmpty()
            }
        }

        override fun flush() {
            // Logging writes each line at once.
        }

        override fun close() {
            // Nothing is held.
        }
    }

    /** Java's levels in ours: below INFO is DEBUG, SEVERE is ERROR. */
    internal fun levelOf(level: Level): LogLevel =
        when {
            level.intValue() >= Level.SEVERE.intValue() -> LogLevel.ERROR
            level.intValue() >= Level.WARNING.intValue() -> LogLevel.WARN
            level.intValue() >= Level.INFO.intValue() -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
}
