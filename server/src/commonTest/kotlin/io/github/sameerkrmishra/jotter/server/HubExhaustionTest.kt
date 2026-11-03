// Tests pick their threads: real time, for the timeouts of a real server.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
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
 * Clients that never read their answers, and failures inside the hub: none of it may take more
 * than the limits, stop the hub from serving, or leave a status that says it serves when it does
 * not.
 */
class HubExhaustionTest {
    private val hubs = TestHubs()
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun stopHubs() =
        kotlinx.coroutines.runBlocking {
            sockets.forEach { it.close() }
            hubs.close()
        }

    private fun test(body: suspend () -> Unit): TestResult =
        runTest(timeout = 3.minutes) { withContext(Dispatchers.Default) { body() } }

    /** Asks for [path] over a new connection and never reads the answer. */
    private suspend fun requestWithoutReading(
        hub: StartedHub,
        path: String,
    ) {
        val socket = rawConnection(hub.port).also { sockets += it }
        val output = socket.openWriteChannel()
        output.writeFully(request("GET ${PeerProtocol.BLOB}?path=$path HTTP/1.1", hub.token))
        output.flush()
    }

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
    fun clientsThatNeverReadCannotOpenMoreThanTheLimit(): TestResult =
        test {
            // Repro A of the review: waves of clients that never read a 4 MiB answer.
            val limits = HubLimits(maxConnectionsPerClient = 16, requestDeadline = 3.seconds)
            val hub = hubs.start(limits)
            hub.localStore.writeNew("four", ByteArray(4 * 1024 * 1024))
            repeat(4) {
                repeat(10) { requestWithoutReading(hub, "four") }
                delay(300.milliseconds)
                val open = hub.hub.openConnections
                assertTrue(open <= limits.maxConnections, "$open connections are open")
            }
            // The deadline cuts them, and the slots come back.
            eventually(30.seconds) { hub.hub.openConnections == 0 }
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun clientsThatNeverReadLargeFilesKeepTheHubServing(): TestResult =
        test {
            // Repro B of the review: 15 clients that never read the answer of a 32 MiB file.
            val limits =
                HubLimits(
                    maxConnectionsPerClient = 16,
                    maxTransfers = 2,
                    requestDeadline = 3.seconds,
                )
            val hub = hubs.start(limits)
            hub.localStore.writeNew("big", ByteArray(PeerProtocol.MAX_FILE_BYTES))
            repeat(15) { requestWithoutReading(hub, "big") }
            delay(500.milliseconds)
            assertTrue(hub.hub.openConnections <= limits.maxConnections)
            // A slot is left for a ping, which needs no transfer.
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
            assertIs<HubStatus.Running>(hub.hub.status.value)
            eventually(1.minutes) { hub.hub.openConnections == 0 }
            assertEquals(1, hubs.client(hub).read("big").size / PeerProtocol.MAX_FILE_BYTES)
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
            val hub = PeerHub(config, ErrorStore(), {}, FileSystem.SYSTEM, Dispatchers.Default)
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
    fun stoppingIsBoundedWhileClientsDoNotRead(): TestResult =
        test {
            val hub = hubs.start(HubLimits(maxConnectionsPerClient = 16))
            hub.localStore.writeNew("big", ByteArray(PeerProtocol.MAX_FILE_BYTES))
            repeat(6) { requestWithoutReading(hub, "big") }
            delay(500.milliseconds)
            val started = TimeSource.Monotonic.markNow()
            hub.hub.stop()
            assertTrue(started.elapsedNow() < 15.seconds, "${started.elapsedNow()}")
            assertEquals(HubStatus.Stopped, hub.hub.status.value)
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
}
