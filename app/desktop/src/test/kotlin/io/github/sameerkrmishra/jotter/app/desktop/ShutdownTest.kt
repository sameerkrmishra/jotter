package io.github.sameerkrmishra.jotter.app.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    ) = Shutdown(awtAlive, exit, { halts.put(it) }, awtWaitMs, haltAfterMs, { disposals.put(Unit) })

    private val disposals = LinkedBlockingQueue<Unit>()

    @Test
    fun afterASignalItRefusesToRunBeforeTheStoreIsClosed() {
        val shutdown = shutdown(haltAfterMs = 50)

        assertFailsWith<IllegalStateException> { shutdown.afterSignal() }

        assertNull(halts.poll(300, TimeUnit.MILLISECONDS))
        assertNull(disposals.poll())
    }

    @Test
    fun afterASignalItDisposesTheWindowsWaitsForAwtAndHaltsAHungExit() {
        val start = System.nanoTime()
        val shutdown =
            shutdown(
                awtAlive = { System.nanoTime() - start < 200_000_000 },
                haltAfterMs = 500,
            )
        shutdown.closed()

        shutdown.afterSignal()

        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(Unit, disposals.poll())
        assertTrue(waitedMs >= 200, "waited $waitedMs ms")
        // The VM would end when the hook returns; here it does not, so the watchdog halts it.
        assertEquals(1, halts.poll(5, TimeUnit.SECONDS))
        assertNull(exits.poll())
    }

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
    fun awtCountsAsAliveWhileItsEventQueueOrShutdownThreadRuns() {
        assertFalse(awtThreadsAlive(), "no AWT in this test")
        for (name in listOf("AWT-EventQueue-0", "AWT-Shutdown")) {
            val stop = CountDownLatch(1)
            val awt = thread(name = name, isDaemon = true) { stop.await() }
            try {
                assertTrue(awtThreadsAlive(), name)
            } finally {
                stop.countDown()
                awt.join(5_000)
            }
            assertFalse(awtThreadsAlive(), "$name ended")
        }
        val other = CountDownLatch(1)
        val unrelated = thread(name = "AWT-XAWT", isDaemon = true) { other.await() }
        assertFalse(awtThreadsAlive(), "the toolkit thread does not end by itself")
        other.countDown()
        unrelated.join(5_000)
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
