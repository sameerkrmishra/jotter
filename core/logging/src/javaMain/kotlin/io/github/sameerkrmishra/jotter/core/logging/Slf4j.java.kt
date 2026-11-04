package io.github.sameerkrmishra.jotter.core.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * The SLF4J backend of the apps, found by SLF4J through `META-INF/services`. Ktor logs through
 * SLF4J; without a backend, SLF4J printed three "no providers" warnings on stderr and dropped every
 * line (docs/milestones/m4.md). Now each line goes to [Logging], at its level, redacted like any
 * other. TRACE is never logged: Ktor writes each request's URL and headers there.
 */
public class JotterSlf4jProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Slf4jLogger>()
    private val factory = ILoggerFactory { name -> loggers.getOrPut(name) { Slf4jLogger(name) } }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory

    override fun getMarkerFactory(): IMarkerFactory = markers

    override fun getMDCAdapter(): MDCAdapter = mdc

    override fun getRequestedApiVersion(): String = SLF4J_API_VERSION

    override fun initialize() {
        // Nothing to set up: Logging is installed by the app.
    }

    private companion object {
        /** The SLF4J API this provider is written for. */
        const val SLF4J_API_VERSION = "2.0.99"
    }
}

/** One SLF4J logger, writing to [Logging] under the last part of its name, such as `HttpClient`. */
internal class Slf4jLogger(
    private val loggerName: String,
) : LegacyAbstractLogger() {
    private val logger = Logger(loggerName.substringAfterLast('.'))

    override fun getName(): String = loggerName

    override fun isTraceEnabled(): Boolean = false

    override fun isDebugEnabled(): Boolean = Logging.isEnabled(LogLevel.DEBUG)

    override fun isInfoEnabled(): Boolean = Logging.isEnabled(LogLevel.INFO)

    override fun isWarnEnabled(): Boolean = Logging.isEnabled(LogLevel.WARN)

    override fun isErrorEnabled(): Boolean = Logging.isEnabled(LogLevel.ERROR)

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val ours =
            when (level) {
                Level.TRACE -> return
                Level.DEBUG -> LogLevel.DEBUG
                Level.INFO -> LogLevel.INFO
                Level.WARN -> LogLevel.WARN
                Level.ERROR -> LogLevel.ERROR
            }
        logger.log(ours, throwable) {
            MessageFormatter.basicArrayFormat(messagePattern, arguments).orEmpty()
        }
    }
}
