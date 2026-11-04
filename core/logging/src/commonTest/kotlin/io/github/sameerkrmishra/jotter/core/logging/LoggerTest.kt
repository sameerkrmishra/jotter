package io.github.sameerkrmishra.jotter.core.logging

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A sink that keeps what it gets. */
class MemorySink : LogSink {
    val records = mutableListOf<LogRecord>()

    override fun write(record: LogRecord) {
        records += record
    }

    val text: String get() = records.joinToString("") { it.format() }
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-11-04T10:00:00.123Z")
}

class LoggerTest {
    private val sink = MemorySink()
    private val logger = Logger("sync")

    @AfterTest
    fun uninstall() {
        Logging.uninstall()
    }

    @Test
    fun nothingIsWrittenBeforeInstall() {
        var built = false
        logger.error {
            built = true
            "lost"
        }
        assertFalse(built)
        assertNull(Logging.level)
    }

    @Test
    fun linesBelowTheLevelAreNotEvenBuilt() {
        Logging.install(sink, LogLevel.INFO)
        var built = false
        logger.debug {
            built = true
            "detail"
        }
        logger.info { "started" }
        logger.warn { "slow" }
        logger.error { "failed" }
        assertFalse(built)
        assertEquals(
            listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            sink.records.map { it.level },
        )
    }

    @Test
    fun debugIsWrittenAtDebug() {
        Logging.install(sink, LogLevel.DEBUG)
        logger.debug { "detail" }
        assertEquals("detail", sink.records.single().message)
    }

    @Test
    fun aLineHasTimeLevelTagAndMessage() {
        Logging.install(sink, LogLevel.INFO, FixedClock)
        logger.info { "Synced backend b1" }
        assertEquals("2026-11-04T10:00:00.123Z INFO sync: Synced backend b1\n", sink.text)
    }

    @Test
    fun aFailureFollowsIndented() {
        Logging.install(sink, LogLevel.INFO, FixedClock)
        logger.warn(TestFailure("disk unplugged")) { "Sync failed" }
        val lines = sink.text.lines()
        assertEquals("2026-11-04T10:00:00.123Z WARN sync: Sync failed", lines[0])
        assertEquals(
            "  io.github.sameerkrmishra.jotter.core.logging.TestFailure: disk unplugged",
            lines[1],
        )
    }

    @Test
    fun messagesAreRedactedAndKeptOnOneLine() {
        Logging.install(sink)
        logger.info {
            "GET https://me:hunter2@dav.example.org/x\nAuthorization: Basic bWU6aHVudGVyMg=="
        }
        val message = sink.records.single().message
        assertFalse("hunter2" in message)
        assertFalse("bWU6aHVudGVyMg" in message)
        assertFalse('\n' in message)
    }

    @Test
    fun aLongMessageIsCut() {
        Logging.install(sink)
        logger.info { "x".repeat(MAX_MESSAGE_CHARS * 2) }
        assertEquals(
            MAX_MESSAGE_CHARS + 3,
            sink.records
                .single()
                .message.length,
        )
    }

    @Test
    fun aSinkThatThrowsLosesTheLineOnly() {
        Logging.install({ throw IllegalStateException("full") })
        logger.error { "still running" }
        Logging.install(sink)
        logger.info { "next" }
        assertEquals("next", sink.records.single().message)
    }

    @Test
    fun uninstallStopsWriting() {
        Logging.install(sink)
        Logging.uninstall()
        logger.error { "lost" }
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun levelsParseInAnyCase() {
        assertEquals(LogLevel.DEBUG, LogLevel.parse(" debug "))
        assertEquals(LogLevel.WARN, LogLevel.parse("WARN"))
        assertNull(LogLevel.parse("verbose"))
        assertNull(LogLevel.parse(null))
    }

    @Test
    fun aRecordWithoutFailureHasNone() {
        Logging.install(sink)
        logger.log(LogLevel.INFO, null) { "plain" }
        assertNull(sink.records.single().failure)
    }
}

/** An exception of this project's package, whose message counts as safe to log. */
class TestFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
