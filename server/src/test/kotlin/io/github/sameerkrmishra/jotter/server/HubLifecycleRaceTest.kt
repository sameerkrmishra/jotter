// Tests pick their threads: real time and real threads, for races with a real server.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.Reachability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Stopping while connections arrive, a watch that fails, and churn of client addresses. */
class HubLifecycleRaceTest {
    private val hubs = TestHubs()
    private val sockets = CopyOnWriteArrayList<Socket>()

    @AfterTest
    fun stopHubs() =
        kotlinx.coroutines.runBlocking {
            sockets.forEach { it.close() }
            hubs.close()
        }

    private fun test(body: suspend () -> Unit): TestResult =
        runTest(timeout = 3.minutes) { withContext(Dispatchers.IO) { body() } }

    private suspend fun eventually(
        within: Duration,
        condition: () -> Boolean,
    ) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            assertTrue(started.elapsedNow() < within, "not within $within")
            delay(20.milliseconds)
        }
    }

    @Test
    fun stoppingWhileConnectionsArriveLeavesNothingOpen(): TestResult =
        test {
            val baseline = openDescriptors()
            repeat(ROUNDS) { round ->
                val hub = hubs.start(HubLimits(maxConnectionsPerClient = 16))
                val opened = CopyOnWriteArrayList<Socket>()
                // Clients open idle connections while the hub stops, so that some arrive while
                // the acceptor is admitting them.
                val openers =
                    List(OPENERS) {
                        thread {
                            try {
                                repeat(CONNECTIONS_EACH) { opened += rawConnection(hub.port) }
                            } catch (_: java.io.IOException) {
                                // The hub had stopped.
                            }
                        }
                    }
                // Stop at a different moment in each round.
                delay((round % STOP_SPREAD_MILLIS).milliseconds)
                hub.hub.stop()
                openers.forEach(Thread::join)
                // The clients still hold their sockets, so a connection the hub forgot stays open
                // on its side, with its thread blocked in a read.
                delay(200.milliseconds)
                assertEquals(emptyList(), hubThreads(), "hub threads left in round $round")
                val held = openDescriptors() - baseline - opened.size
                assertTrue(
                    held <= SLACK_DESCRIPTORS,
                    "round $round: the hub holds $held descriptors",
                )
                opened.forEach { it.close() }
            }
        }

    @Test
    fun aFailingWatchStopsTheHubAndClosesItsConnections(): TestResult =
        test {
            val hub = hubs.start(HubLimits(headerTimeout = 1.minutes, idleTimeout = 1.minutes))
            val idle = rawConnection(hub.port).also { sockets += it }
            eventually(5.seconds) { hub.hub.openConnections == 1 }
            hub.hub.failWatch(IllegalStateException("simulated"))
            eventually(5.seconds) { hub.hub.status.value is HubStatus.Failed }
            assertContains((hub.hub.status.value as HubStatus.Failed).message, "watch")
            // The idle connection is closed at once, not after its minute.
            idle.soTimeout = 5_000
            assertEquals(-1, idle.getInputStream().read())
            eventually(5.seconds) { hub.hub.openConnections == 0 }
            // Starting again serves again, with a watch of its own.
            hub.hub.start()
            assertIs<HubStatus.Running>(hub.hub.status.value)
        }

    @Test
    fun addressesLeaveTheCapsTableWhenTheirConnectionsEnd(): TestResult =
        test {
            val hub = hubs.start()
            val client = hubs.client(hub)
            repeat(CHURN) { assertEquals(Reachability.Reachable, client.ping()) }
            eventually(5.seconds) { hub.hub.openConnections == 0 }
            assertEquals(0, hub.hub.trackedAddresses)
        }

    private companion object {
        const val ROUNDS = 60
        const val OPENERS = 4

        // 40 in all: within the 16 slots and the listen queue of 50, so no connect blocks.
        const val CONNECTIONS_EACH = 10
        const val STOP_SPREAD_MILLIS = 12
        const val CHURN = 50
        const val SLACK_DESCRIPTORS = 4

        fun openDescriptors(): Int = File("/proc/self/fd").list()!!.size

        fun hubThreads(): List<String> =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.isAlive && it.name.startsWith("jotter-hub-") }
                .map { it.name }
    }
}
