package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

/** Requests that the peer client never sends, and stores that fail. */
class HubProtocolTest {
    private val hubs = TestHubs()

    @AfterTest
    fun stopHubs() = kotlinx.coroutines.runBlocking { hubs.close() }

    private fun test(body: suspend () -> Unit): TestResult = runTest(timeout = 1.minutes) { body() }

    @Test
    fun requestsOutsideTheProtocolAreRefused(): TestResult =
        test {
            val hub = hubs.start()
            val token = hub.token
            val bad =
                listOf(
                    request("GET /jotter-peer/v2/ping HTTP/1.1", token),
                    request("GET ${PeerProtocol.PING}?x=1 HTTP/1.1", token),
                    request("DELETE ${PeerProtocol.BLOB}?path=a HTTP/1.1", token),
                    request(
                        "POST ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        token,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                    request(
                        "GET ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        token,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                    request(
                        "GET ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        token,
                        "Transfer-Encoding: chunked",
                    ),
                    request("GET ${PeerProtocol.BLOB} HTTP/1.1", token),
                    request("GET ${PeerProtocol.BLOB}?path=a&path=b HTTP/1.1", token),
                    request("GET ${PeerProtocol.BLOB}?path=a&prefix=b HTTP/1.1", token),
                    request("GET ${PeerProtocol.LIST}?path=a HTTP/1.1", token),
                    request("GET ${PeerProtocol.LIST}?prefix=%zz HTTP/1.1", token),
                    request(
                        "PUT ${PeerProtocol.BLOB}?prefix=a HTTP/1.1",
                        token,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                    request(
                        "PUT ${PeerProtocol.LIST}?prefix=a HTTP/1.1",
                        token,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                    request("PUT ${PeerProtocol.PING} HTTP/1.1", token, "Content-Length: 0"),
                )
            bad.forEach { bytes ->
                val answer = rawExchange(hub.port, bytes)
                assertEquals(400, statusOf(answer), bytes.decodeToString())
                assertContains(answer, "\"BAD_REQUEST\"")
            }
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun aHeadThatDoesNotParseOrBreaksOffGetsNoAnswer(): TestResult =
        test {
            val hub = hubs.start()
            val broken =
                listOf(
                    "",
                    "GET ${PeerProtocol.PING} HTTP/1.1\r\nAuthorization: Bearer ${hub.token}",
                    "\r\n\r\n",
                    "NONSENSE\r\n\r\n",
                    "GET ${PeerProtocol.PING} HTTP/9.9\r\n\r\n",
                )
            broken.forEach { text ->
                assertEquals(
                    "",
                    rawExchange(hub.port, text.encodeToByteArray(), close = true),
                    text,
                )
            }
            // The hub serves on.
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun noPathLeavesTheStore(): TestResult =
        test {
            val hub = hubs.start()
            val outside = hub.storeRoot.parent!! / "outside"
            FileSystem.SYSTEM.write(outside) { writeUtf8("secret") }
            val paths =
                listOf(
                    "..%2Foutside",
                    "%2E%2E/outside",
                    "a/../../outside",
                    "%2Fetc%2Fpasswd",
                    "..%5Coutside",
                    "C:x",
                    "a%00b",
                )
            paths.forEach { path ->
                listOf(
                    request("GET ${PeerProtocol.BLOB}?path=$path HTTP/1.1", hub.token),
                    request("GET ${PeerProtocol.LIST}?prefix=$path HTTP/1.1", hub.token),
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=$path HTTP/1.1",
                        hub.token,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                ).forEach { bytes ->
                    val answer = rawExchange(hub.port, bytes)
                    assertEquals(400, statusOf(answer), bytes.decodeToString())
                    assertContains(answer, "\"INVALID_PATH\"")
                    assertFalse("secret" in answer)
                }
            }
            assertEquals("secret", FileSystem.SYSTEM.read(outside) { readUtf8() })
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun namesWithQueryCharactersArriveAsTheyWere(): TestResult =
        test {
            val hub = hubs.start()
            val client = hubs.client(hub)
            val path = "dir/a b+c&d=e%f#g?h;i 50%2F"
            client.writeNew(path, byteArrayOf(7))
            assertEquals(listOf(path), hub.localStore.list("").map { it.path })
            assertEquals(listOf(path), client.list("dir").map { it.path })
            assertContentEquals(byteArrayOf(7), client.read(path))
        }

    @Test
    fun aFileLargerThanTheLimitIsNotServed(): TestResult =
        test {
            val hub = hubs.start()
            hub.localStore.writeNew("huge", ByteArray(PeerProtocol.MAX_FILE_BYTES + 1))
            assertFailsWith<BlobStoreFailureException> { hubs.client(hub).read("huge") }
        }

    @Test
    fun storeFailuresReachTheClientAsFailures(): TestResult =
        test {
            val answer = handle(FailingStore(BlobStoreFailureException("disk full")))
            assertEquals(500, answer.status)
            assertContains(answer.body.decodeToString(), "disk full")
        }

    @Test
    fun aBugInTheStoreIsAFailureWithoutItsMessage(): TestResult =
        test {
            val answer = handle(FailingStore(IllegalStateException("internal detail")))
            assertEquals(500, answer.status)
            assertFalse("internal detail" in answer.body.decodeToString())
        }

    private suspend fun handle(store: BlobStore): Reply =
        PeerHandler(store, { true }, Semaphore(1)).handle(
            RequestHead("GET", "${PeerProtocol.BLOB}?path=a", null, null, false),
        ) { error("no body") }

    /** A store whose every call fails with [failure]. */
    private class FailingStore(
        private val failure: Exception,
    ) : BlobStore {
        override suspend fun list(prefix: String): List<BlobInfo> = throw failure

        override suspend fun read(path: String): ByteArray = throw failure

        override suspend fun writeNew(
            path: String,
            bytes: ByteArray,
        ) = throw failure

        override suspend fun ping(): Reachability = throw failure
    }
}
