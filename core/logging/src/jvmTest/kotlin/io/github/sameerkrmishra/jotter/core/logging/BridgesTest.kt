package io.github.sameerkrmishra.jotter.core.logging

import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import org.slf4j.event.Level
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.logging.Level as JavaLevel
import java.util.logging.Logger as JavaLogger

class BridgesTest {
    private val sink = MemorySink()

    @AfterTest
    fun uninstall() {
        Logging.uninstall()
    }

    @Test
    fun slf4jFindsThisProviderAndPrintsNoWarning() {
        val stderr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            Logging.install(sink, LogLevel.DEBUG)
            LoggerFactory
                .getLogger(
                    "io.ktor.client.HttpClient",
                ).debug("Sending {} to {}", "PUT", "x")
        } finally {
            System.setErr(stderr)
        }
        assertFalse("SLF4J" in captured.toString())
        val record = sink.records.single()
        assertEquals("HttpClient", record.tag)
        assertEquals("Sending PUT to x", record.message)
        assertEquals(LogLevel.DEBUG, record.level)
    }

    @Test
    fun slf4jLevelsMapToOursAndTraceIsDropped() {
        Logging.install(sink, LogLevel.DEBUG)
        val logger = LoggerFactory.getLogger("a.b.Client")
        assertFalse(logger.isTraceEnabled)
        logger.trace("Authorization: {}", "never")
        logger.info("i")
        logger.warn("w", TestFailure("why"))
        logger.error(MarkerFactory.getMarker("m"), "e")
        logger.atLevel(Level.TRACE).log("t")
        assertEquals(
            listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            sink.records.map { it.level },
        )
        assertTrue(
            sink.records[1]
                .failure
                .orEmpty()
                .contains("TestFailure: why"),
        )
        assertSame(logger, LoggerFactory.getLogger("a.b.Client"))
        assertEquals("a.b.Client", logger.name)
    }

    @Test
    fun slf4jAsksOurLevel() {
        Logging.install(sink, LogLevel.WARN)
        val logger = LoggerFactory.getLogger("x")
        assertFalse(logger.isDebugEnabled)
        assertFalse(logger.isInfoEnabled)
        assertTrue(logger.isWarnEnabled)
        assertTrue(logger.isErrorEnabled)
        logger.debug("dropped")
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun slf4jLinesAreRedacted() {
        Logging.install(sink)
        LoggerFactory.getLogger("x").info("REQUEST: {}", "https://me:pw@host/")
        assertFalse("pw@" in sink.records.single().message)
    }

    @Test
    fun okHttpLogsReachTheLog() {
        Logging.install(sink, LogLevel.INFO)
        JavaLogging.route()
        JavaLogging.route()
        val okhttp = JavaLogger.getLogger("okhttp3.OkHttpClient")
        assertEquals(1, okhttp.handlers.size)
        assertFalse(okhttp.useParentHandlers)
        okhttp.log(JavaLevel.WARNING, "A connection to {0} was leaked", "https://hub/")
        okhttp.fine("dropped below INFO")
        JavaLogger.getLogger("okhttp3.internal.concurrent").info("child")
        assertEquals(
            listOf("A connection to https://hub/ was leaked", "child"),
            sink.records.map { it.message },
        )
        assertEquals("OkHttpClient", sink.records[0].tag)
        assertEquals(LogLevel.WARN, sink.records[0].level)
    }

    @Test
    fun atDebugFineLinesAreRouted() {
        Logging.install(sink, LogLevel.DEBUG)
        JavaLogging.route(listOf("test.debug"))
        JavaLogger.getLogger("test.debug").log(JavaLevel.FINE, "fine", TestFailure("x"))
        assertEquals(LogLevel.DEBUG, sink.records.single().level)
        assertTrue(
            sink.records
                .single()
                .failure
                .orEmpty()
                .startsWith("io.github"),
        )
    }

    @Test
    fun javaLevelsMap() {
        assertEquals(LogLevel.ERROR, JavaLogging.levelOf(JavaLevel.SEVERE))
        assertEquals(LogLevel.WARN, JavaLogging.levelOf(JavaLevel.WARNING))
        assertEquals(LogLevel.INFO, JavaLogging.levelOf(JavaLevel.INFO))
        assertEquals(LogLevel.DEBUG, JavaLogging.levelOf(JavaLevel.FINEST))
    }
}
