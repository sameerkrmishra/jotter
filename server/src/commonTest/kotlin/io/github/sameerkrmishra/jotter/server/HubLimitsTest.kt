// Tests pick their threads: real time, for the timeouts of a real server.
@file:Suppress("InjectDispatcher")

package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.contract.InMemoryBlobStore
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** What keeps one bad client from exhausting the PC: sizes, time, connections and transfers. */
class HubLimitsTest {
    private val hubs = TestHubs()

    @AfterTest
    fun stopHubs() = kotlinx.coroutines.runBlocking { hubs.close() }

    // Real time throughout: these tests wait for the hub's timeouts.
    private fun test(body: suspend () -> Unit): TestResult =
        runTest(timeout = 2.minutes) { withContext(Dispatchers.Default) { body() } }

    @Test
    fun aFileOfExactlyTheLimitPassesThrough(): TestResult =
        test {
            val hub = hubs.start()
            val client = hubs.client(hub)
            val bytes = ByteArray(PeerProtocol.MAX_FILE_BYTES) { (it % 251).toByte() }
            client.writeNew("big", bytes)
            assertContentEquals(bytes, client.read("big"))
        }

    @Test
    fun theClientRefusesALargerFileWithoutSendingIt(): TestResult =
        test {
            val hub = hubs.start()
            hub.hub.stop()
            // The hub is down, so a failure other than "unavailable" was decided before sending.
            assertFailsWith<BlobStoreFailureException> {
                hubs.client(hub).writeNew("big", ByteArray(PeerProtocol.MAX_FILE_BYTES + 1))
            }
        }

    @Test
    fun theHubRefusesALargerBodyBeforeReadingIt(): TestResult =
        test {
            val hub = hubs.start()
            val answer =
                rawExchange(
                    hub.port,
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=big HTTP/1.1",
                        hub.token,
                        "Content-Length: ${PeerProtocol.MAX_FILE_BYTES + 1}",
                    ),
                )
            assertEquals(413, statusOf(answer), answer)
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun aWriteNeedsAPlainLength(): TestResult =
        test {
            val hub = hubs.start()
            val line = "PUT ${PeerProtocol.BLOB}?path=a HTTP/1.1"
            val chunked = "Transfer-Encoding: chunked"
            assertEquals(411, statusOf(rawExchange(hub.port, request(line, hub.token))))
            val withChunks =
                request(line, hub.token, chunked, body = "1\r\na\r\n0\r\n\r\n".encodeToByteArray())
            assertEquals(411, statusOf(rawExchange(hub.port, withChunks)))
            listOf("-1", "1x", "", "9".repeat(13)).forEach { length ->
                val answer =
                    rawExchange(hub.port, request(line, hub.token, "Content-Length: $length"))
                assertEquals(400, statusOf(answer), "Content-Length: $length")
            }
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun anOversizedHeadIsDropped(): TestResult =
        test {
            val hub = hubs.start()
            val huge = "X-Filler: " + "a".repeat(MAX_HEAD_BYTES)
            val answer =
                rawExchange(hub.port, request("GET ${PeerProtocol.PING} HTTP/1.1", hub.token, huge))
            assertEquals("", answer)
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun aClientThatStallsInItsHeadIsDropped(): TestResult =
        test {
            val hub = hubs.start(quickLimits)
            val started = TimeSource.Monotonic.markNow()
            rawConnection(hub.port).use { socket ->
                val output = socket.openWriteChannel()
                output.writeFully("GET ${PeerProtocol.PING} HTTP/1.1\r\n".encodeToByteArray())
                output.flush()
                assertEquals(0, socket.openReadChannel().readAll().size)
            }
            assertTrue(started.elapsedNow() >= quickLimits.headerTimeout)
        }

    @Test
    fun aBodyThatStallsIsDroppedAndNothingIsWritten(): TestResult =
        test {
            val hub = hubs.start(quickLimits)
            rawConnection(hub.port).use { socket ->
                val output = socket.openWriteChannel()
                output.writeFully(
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        hub.token,
                        "Content-Length: 100",
                    ) +
                        ByteArray(10),
                )
                output.flush()
                // The hub drops the connection without an answer once the body stalls.
                assertEquals(0, socket.openReadChannel().readAll().size)
            }
            assertEquals(emptyList(), hub.localStore.list(""))
            hubs.client(hub).writeNew("a", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), hub.localStore.read("a"))
        }

    @Test
    fun aBodyThatBreaksOffWritesNothing(): TestResult =
        test {
            val hub = hubs.start()
            rawConnection(hub.port).use { socket ->
                val output = socket.openWriteChannel()
                output.writeFully(
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        hub.token,
                        "Content-Length: 100",
                    ) +
                        ByteArray(10),
                )
                output.flushAndClose()
                assertEquals(0, socket.openReadChannel().readAll().size)
            }
            assertEquals(emptyList(), hub.localStore.list(""))
            hubs.client(hub).writeNew("a", byteArrayOf(2))
            assertContentEquals(byteArrayOf(2), hub.localStore.read("a"))
        }

    @Test
    fun connectionsBeyondTheLimitWaitUntilOneEnds(): TestResult =
        test {
            val limits = quickLimits.copy(maxConnections = 2)
            val hub = hubs.start(limits)
            val idle = List(limits.maxConnections) { rawConnection(hub.port) }
            try {
                val started = TimeSource.Monotonic.markNow()
                // Both slots are held by connections that send nothing. The ping waits in the
                // system's queue until the hub drops one of them, then is served.
                assertEquals(Reachability.Reachable, hubs.client(hub).ping())
                assertTrue(started.elapsedNow() >= limits.headerTimeout - 100.milliseconds)
            } finally {
                idle.forEach { it.close() }
            }
        }

    @Test
    fun transfersBeyondTheLimitWait(): TestResult =
        test {
            val limits = quickLimits.copy(maxTransfers = 1)
            val hub = hubs.start(limits)
            val client = hubs.client(hub)
            client.writeNew("a", byteArrayOf(1))
            rawConnection(hub.port).use { socket ->
                // Holds the only transfer, with a body that stalls.
                val output = socket.openWriteChannel()
                output.writeFully(
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=b HTTP/1.1",
                        hub.token,
                        "Content-Length: 100",
                    ) +
                        ByteArray(1),
                )
                output.flush()
                delay(100.milliseconds)
                val started = TimeSource.Monotonic.markNow()
                assertContentEquals(byteArrayOf(1), client.read("a"))
                assertTrue(started.elapsedNow() >= limits.idleTimeout - 200.milliseconds)
            }
        }

    @Test
    fun aReadKeepsItsTransferUntilTheAnswerIsSent(): TestResult =
        test {
            coroutineScope {
                // The handler alone: a slow reader cannot be made reliably, as sockets buffer much.
                val transfers = Semaphore(1)
                val store = InMemoryBlobStore().apply { writeNew("a", byteArrayOf(1)) }
                val handler = PeerHandler(store, { true }, transfers)
                val head = RequestHead("GET", "${PeerProtocol.BLOB}?path=a", null, null, false)
                val first = handler.handle(head) { error("no body") }
                assertEquals(0, transfers.availablePermits)
                val second = async { handler.handle(head) { error("no body") } }
                delay(100.milliseconds)
                assertTrue(second.isActive, "the second read waits while the first answer is held")
                first.release()
                assertContentEquals(byteArrayOf(1), second.await().body)
                second.await().release()
                assertEquals(1, transfers.availablePermits)
                // A failed read gives its transfer back at once.
                assertEquals(
                    404,
                    handler
                        .handle(
                            head.copy(target = "${PeerProtocol.BLOB}?path=b"),
                        ) {
                            error("no")
                        }.status,
                )
                assertEquals(1, transfers.availablePermits)
            }
        }

    @Test
    fun concurrentWritersThroughTheHubAndOnThePcHaveOneWinner(): TestResult =
        test {
            val hub = hubs.start()
            val phone = hubs.client(hub)
            val laptop = hubs.client(hub, hub.hub.pair("laptop").token)
            val writers: List<BlobStore> = listOf(phone, laptop, hub.localStore, phone, laptop)
            repeat(ROUNDS) { round ->
                val path = "race/$round"
                val winners =
                    coroutineScope {
                        writers
                            .mapIndexed { index, store ->
                                async(Dispatchers.Default) {
                                    try {
                                        store.writeNew(path, ByteArray(SIZE) { index.toByte() })
                                        index
                                    } catch (_: BlobAlreadyExistsException) {
                                        null
                                    }
                                }
                            }.awaitAll()
                    }.filterNotNull()
                assertEquals(1, winners.size, "winners of $path: $winners")
                val expected = ByteArray(SIZE) { winners.single().toByte() }
                assertContentEquals(expected, phone.read(path))
                assertContentEquals(expected, hub.localStore.read(path))
            }
        }

    private companion object {
        const val ROUNDS = 20
        const val SIZE = 64 * 1024
    }
}
