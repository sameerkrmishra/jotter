package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Nothing reaches the store without a valid token, and no answer carries a token. */
class HubAuthenticationTest {
    private val hubs = TestHubs()

    @AfterTest
    fun stopHubs() = kotlinx.coroutines.runBlocking { hubs.close() }

    private fun test(body: suspend () -> Unit): TestResult = runTest(timeout = 1.minutes) { body() }

    @Test
    fun aWrongTokenIsRefusedByEveryMethodAndNothingIsWritten(): TestResult =
        test {
            val hub = hubs.start()
            val stranger = hubs.client(hub, hub.token.dropLast(1) + "x")
            val ping = stranger.ping()
            assertEquals(UnreachableReason.FAILED, (ping as Reachability.Unreachable).reason)
            assertContains(ping.detail, "did not accept this device's token")
            assertFailsWith<BackendUnavailableException> { stranger.list("") }
            assertFailsWith<BackendUnavailableException> { stranger.read("a") }
            assertFailsWith<BackendUnavailableException> { stranger.writeNew("a", byteArrayOf(1)) }
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun anUnpairedDeviceIsRefusedAtOnceByARunningHub(): TestResult =
        test {
            val hub = hubs.start()
            val other = hub.hub.pair("phone")
            val client = hubs.client(hub, other.token)
            assertEquals(Reachability.Reachable, client.ping())
            assertTrue(hub.hub.unpair(other.device.id))
            assertTrue(client.ping() is Reachability.Unreachable)
            // The first device is untouched.
            assertEquals(Reachability.Reachable, hubs.client(hub).ping())
        }

    @Test
    fun requestsWithoutAValidTokenGet401AndTheProtocolHeader(): TestResult =
        test {
            val hub = hubs.start()
            val id = hub.token.substringBefore('.')
            val refused =
                listOf(
                    request("GET ${PeerProtocol.PING} HTTP/1.1", null),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", null, "Authorization: Basic YTpi"),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", ""),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", id),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", "$id."),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", "$id.wrong"),
                    request(
                        "GET ${PeerProtocol.PING} HTTP/1.1",
                        "other." + hub.token.substringAfter('.'),
                    ),
                    // Unknown requests too: the token is checked before anything else.
                    request("GET /anything HTTP/1.1", null),
                    request(
                        "PUT ${PeerProtocol.BLOB}?path=a HTTP/1.1",
                        null,
                        "Content-Length: 1",
                        body = byteArrayOf(1),
                    ),
                )
            refused.forEach { bytes ->
                val answer = rawExchange(hub.port, bytes)
                assertEquals(401, statusOf(answer), answer)
                assertContains(answer, "${PeerProtocol.VERSION_HEADER}: 1")
                assertContains(answer, "\"UNAUTHORIZED\"")
            }
            assertEquals(emptyList(), hub.localStore.list(""))
        }

    @Test
    fun twoAuthorizationHeadersAreRefused(): TestResult =
        test {
            val hub = hubs.start()
            val answer =
                rawExchange(
                    hub.port,
                    request(
                        "GET ${PeerProtocol.PING} HTTP/1.1",
                        hub.token,
                        "Authorization: Bearer x",
                    ),
                )
            // Malformed: the connection closes without an answer.
            assertEquals("", answer)
        }

    @Test
    fun noAnswerCarriesTheToken(): TestResult =
        test {
            val hub = hubs.start()
            hubs.client(hub).writeNew("a/file", byteArrayOf(1))
            val requests =
                listOf(
                    request("GET ${PeerProtocol.PING} HTTP/1.1", hub.token),
                    request("GET ${PeerProtocol.LIST}?prefix= HTTP/1.1", hub.token),
                    request("GET ${PeerProtocol.BLOB}?path=missing HTTP/1.1", hub.token),
                    request("GET ${PeerProtocol.BLOB}?path=a/file HTTP/1.1", hub.token),
                    request("GET /unknown HTTP/1.1", hub.token),
                    request("GET ${PeerProtocol.PING} HTTP/1.1", hub.token + "x"),
                )
            val secret = hub.token.substringAfter('.')
            requests.forEach { bytes ->
                val answer = rawExchange(hub.port, bytes)
                assertFalse(secret in answer, answer)
            }
        }

    @Test
    fun aPairingDoesNotPrintItsToken(): TestResult =
        test {
            val hub = hubs.start()
            val pairing = hub.hub.pair("laptop")
            assertFalse(pairing.token in pairing.toString())
            assertContains(pairing.toString(), "laptop")
        }
}
