// Tests pick their threads: real time, for the timeouts of a real server.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.server

import com.sun.management.OperatingSystemMXBean
import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.io.File
import java.lang.management.ManagementFactory
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Clients that never read their answers, and failures inside the hub. These measure what such
 * clients cost the process, not only what the hub reports: open file descriptors, CPU time, heap
 * after a collection, and slots. Linux only, for `/proc/self/fd`.
 */
class HubExhaustionTest {
    private val hubs = TestHubs()
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun stopHubs() =
        kotlinx.coroutines.runBlocking {
            closeClients()
            hubs.close()
        }

    private fun closeClients() {
        sockets.forEach { it.close() }
        sockets.clear()
    }

    private fun test(body: suspend () -> Unit): TestResult =
        runTest(timeout = 3.minutes) { withContext(Dispatchers.IO) { body() } }

    /** Asks for [path] over a new connection and never reads the answer. */
    private fun requestWithoutReading(
        hub: StartedHub,
        path: String,
    ) {
        val socket = rawConnection(hub.port).also { sockets += it }
        socket.getOutputStream().write(
            request("GET ${PeerProtocol.BLOB}?path=$path HTTP/1.1", hub.token),
        )
        socket.getOutputStream().flush()
    }

    /** The descriptors the hub holds: the process's, less the test's own client sockets. */
    private fun hubDescriptors(baseline: Int): Int = openDescriptors() - baseline - sockets.size

    private suspend fun eventually(
        within: Duration,
        condition: () -> Boolean,
    ) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            assertTrue(started.elapsedNow() < within, "not within $within")
            delay(50.milliseconds)
        }
    }

    @Test
    fun clientsThatNeverReadHoldNoMoreThanTheLimitAndNothingAfterTheDeadline(): TestResult =
        test {
            // Repro A of the review: 40 staggered clients that never read a 4 MiB answer.
            val limits = HubLimits(maxConnectionsPerClient = 16, requestDeadline = 4.seconds)
            val hub = hubs.start(limits)
            hub.localStore.writeNew("four", ByteArray(4 * 1024 * 1024))
            val baseline = openDescriptors()
            repeat(4) {
                repeat(10) { requestWithoutReading(hub, "four") }
                delay(300.milliseconds)
                assertTrue(hub.hub.openConnections <= limits.maxConnections)
                val held = hubDescriptors(baseline)
                println("repro A: open ${hub.hub.openConnections}, hub descriptors $held")
                assertTrue(
                    held <= limits.maxConnections + SLACK_DESCRIPTORS,
                    "the hub holds $held descriptors",
                )
            }
            // While they wait, nothing spins.
            val cpu = cpuSeconds { delay(2.seconds) }
            println("repro A: CPU $cpu s in 2 s")
            assertTrue(cpu < MAX_IDLE_CPU_SECONDS, "$cpu s of CPU in 2 s while clients do not read")
            // The deadline cuts them: their descriptors are closed, though the clients keep theirs.
            eventually(1.minutes) {
                hub.hub.openConnections == 0 &&
                    hubDescriptors(baseline) <= SLACK_DESCRIPTORS
            }
            println(
                "repro A after: hub descriptors ${hubDescriptors(
                    baseline,
                )}, heap ${heapAfterGc() / MIB} MiB",
            )
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun clientsThatNeverReadLargeFilesHoldOnlyTheirTransfers(): TestResult =
        test {
            // Repro B of the review: 15 clients that never read the answer of a 32 MiB file.
            val limits = HubLimits(maxConnectionsPerClient = 16, requestDeadline = 5.seconds)
            val hub = hubs.start(limits)
            hub.localStore.writeNew("big", ByteArray(PeerProtocol.MAX_FILE_BYTES))
            hub.localStore.writeNew("small", byteArrayOf(1))
            val heapBefore = heapAfterGc()
            repeat(15) { requestWithoutReading(hub, "big") }
            delay(1.seconds)
            val heapDuring = heapAfterGc() - heapBefore
            val bound = limits.maxTransfers.toLong() * PeerProtocol.MAX_FILE_BYTES + HEAP_SLACK
            assertTrue(
                heapDuring <= bound,
                "${heapDuring / MIB} MiB held, more than ${bound / MIB}",
            )
            // Slots are left for a ping, which needs no transfer.
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
            // Every transfer is held by a client that does not read: a read waits for the deadline.
            val started = TimeSource.Monotonic.markNow()
            assertContentEquals(byteArrayOf(1), hubs.client(hub).read("small"))
            assertTrue(started.elapsedNow() >= 2.seconds, "read in ${started.elapsedNow()}")
            assertIs<HubStatus.Running>(hub.hub.status.value)
            eventually(1.minutes) { hub.hub.openConnections == 0 }
            closeClients()
            val heapAfter = heapAfterGc() - heapBefore
            println(
                "repro B: heap above baseline during ${heapDuring / MIB} MiB, " +
                    "after ${heapAfter / MIB} MiB",
            )
            assertTrue(heapAfter <= HEAP_SLACK, "memory stays held after the deadline")
        }

    @Test
    fun stoppingClosesEverythingQuicklyWhileClientsDoNotRead(): TestResult =
        test {
            val hub = hubs.start(HubLimits(maxConnectionsPerClient = 16))
            hub.localStore.writeNew("big", ByteArray(PeerProtocol.MAX_FILE_BYTES))
            val baseline = openDescriptors()
            repeat(6) { requestWithoutReading(hub, "big") }
            delay(500.milliseconds)
            val started = TimeSource.Monotonic.markNow()
            hub.hub.stop()
            val stopTime = started.elapsedNow()
            assertTrue(stopTime < 6.seconds, "stop took $stopTime")
            assertEquals(HubStatus.Stopped, hub.hub.status.value)
            // The listening socket is gone as well, so the hub holds fewer than before.
            assertTrue(
                hubDescriptors(baseline) <= 0,
                "the hub still holds ${hubDescriptors(baseline)}",
            )
            val cpu = cpuSeconds { delay(1.seconds) }
            println(
                "stop: $stopTime, hub descriptors after ${hubDescriptors(
                    baseline,
                )}, CPU after $cpu s in 1 s",
            )
            assertTrue(cpu < MAX_IDLE_CPU_SECONDS, "$cpu s of CPU after stop")
        }

    @Test
    fun oneAddressCannotTakeEverySlot(): TestResult =
        test {
            val limits = quickLimits.copy(maxConnectionsPerClient = 2)
            val hub = hubs.start(limits)
            repeat(2) { sockets += rawConnection(hub.port) }
            delay(100.milliseconds)
            // The third connection from 127.0.0.1 is cut at once, not queued until a slot frees.
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<BackendUnavailableException> { hubs.client(hub).read("a") }
            assertTrue(started.elapsedNow() < limits.headerTimeout, "${started.elapsedNow()}")
            // The idle two are dropped after the header timeout, and the address may connect again.
            eventually(10.seconds) { hub.hub.openConnections == 0 }
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun anErrorInTheStoreEndsOneConnectionOnly(): TestResult =
        test {
            val root = hubs.newPath()
            val config = HubConfig(root / "store", root / "devices.json", port = 0)
            val hub = PeerHub(config, ErrorStore(), {}, FileSystem.SYSTEM, Dispatchers.IO)
            try {
                val running = hub.start()
                val client =
                    PeerBlobStore(PeerAddress(LOOPBACK, running.port), hub.pair("phone").token)
                client.use {
                    assertFailsWith<BackendUnavailableException> { it.read("a") }
                    assertEquals(Reachability.Reachable, it.ping())
                    assertIs<HubStatus.Running>(hub.status.value)
                }
            } finally {
                hub.stop()
            }
        }

    @Test
    fun aHubThatStopsAcceptingSaysSo(): TestResult =
        test {
            val hub = hubs.start()
            hub.hub.breakListener()
            eventually(10.seconds) { hub.hub.status.value is HubStatus.Failed }
            assertContains((hub.hub.status.value as HubStatus.Failed).message, "stopped accepting")
            // Starting again serves again.
            val running = hub.hub.start()
            assertIs<HubStatus.Running>(hub.hub.status.value)
            val client = hubs.client(StartedHub(hub.hub, hub.storeRoot, running.port, hub.token))
            assertEquals(Reachability.Reachable, client.ping())
        }

    @Test
    fun invalidUtf8InAPathIsRefused(): TestResult =
        test {
            val hub = hubs.start()
            listOf("%FF", "a%C3%28", "%ED%A0%80", "%4", "a%").forEach { path ->
                val answer =
                    rawExchange(
                        hub.port,
                        request("GET ${PeerProtocol.BLOB}?path=$path HTTP/1.1", hub.token),
                    )
                assertEquals(400, statusOf(answer), path)
                assertContains(answer, "\"BAD_REQUEST\"")
            }
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    /** A store whose `read` fails with an Error, as running out of memory would. */
    private class ErrorStore : BlobStore {
        override suspend fun list(prefix: String): List<BlobInfo> = emptyList()

        override suspend fun read(path: String): ByteArray = throw StoreBrokeError()

        override suspend fun writeNew(
            path: String,
            bytes: ByteArray,
        ) = Unit

        override suspend fun ping(): Reachability = Reachability.Reachable
    }

    private class StoreBrokeError : Error("simulated")

    private companion object {
        const val MIB = 1024L * 1024
        const val SLACK_DESCRIPTORS = 6
        const val HEAP_SLACK = 48 * MIB

        // A spinning thread would take about a second of CPU per second.
        const val MAX_IDLE_CPU_SECONDS = 0.5

        fun openDescriptors(): Int = File("/proc/self/fd").list()!!.size

        fun heapAfterGc(): Long {
            repeat(3) { System.gc() }
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }

        suspend fun cpuSeconds(during: suspend () -> Unit): Double {
            val bean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val before = bean.processCpuTime
            during()
            return (bean.processCpuTime - before) / NANOS_PER_SECOND
        }

        const val NANOS_PER_SECOND = 1e9
    }
}
