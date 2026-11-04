package io.github.sameerkrmishra.jotter.app.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShutdownTest {
    private val exits = LinkedBlockingQueue<Int>()
    private val halts = LinkedBlockingQueue<Int>()

    private fun shutdown(
        awtAlive: () -> Boolean = { false },
        exit: (Int) -> Unit = { exits.put(it) },
        awtWaitMs: Long = 2_000,
        haltAfterMs: Long = 60_000,
    ) = Shutdown(awtAlive, exit, { halts.put(it) }, awtWaitMs, haltAfterMs)

    @Test
    fun exitingBeforeTheStoreIsClosedIsRefusedAndNeitherExitsNorHalts() {
        val shutdown = shutdown(haltAfterMs = 50)

        assertFailsWith<IllegalStateException> { shutdown.exit(0) }

        assertNull(exits.poll(300, TimeUnit.MILLISECONDS))
        assertNull(halts.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun itWaitsForAwtToShutDownThenExits() {
        val start = System.nanoTime()
        val shutdown = shutdown(awtAlive = { System.nanoTime() - start < 300_000_000 })
        shutdown.closed()

        shutdown.exit(0)

        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(0, exits.poll())
        assertTrue(waitedMs in 300..1_900, "waited $waitedMs ms")
        assertNull(halts.poll())
    }

    @Test
    fun itWaitsForAwtOnlyUpToItsBound() {
        val shutdown = shutdown(awtAlive = { true }, awtWaitMs = 200)
        shutdown.closed()
        val start = System.nanoTime()

        shutdown.exit(3)

        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(3, exits.poll())
        assertTrue(waitedMs in 200..1_500, "waited $waitedMs ms")
    }

    @Test
    fun theWatchdogHaltsAProcessWhoseExitHangs() {
        val hung = CountDownLatch(1)
        val shutdown = shutdown(exit = { hung.await() }, haltAfterMs = 200)
        shutdown.closed()

        val exiting = thread(isDaemon = true) { shutdown.exit(0) }

        assertEquals(0, halts.poll(5, TimeUnit.SECONDS))
        hung.countDown()
        exiting.join(5_000)
    }
}
