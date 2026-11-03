package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.peer.PeerAddress
import io.github.sameerkrmishra.jotter.backend.peer.PeerBlobStore
import io.github.sameerkrmishra.jotter.server.HubConfig
import io.github.sameerkrmishra.jotter.server.PeerHub
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sockets that leave only through Tailscale's network, and the hub client on them, on the JVM:
 * binding records instead of `Network.bindSocket`, which the device tests use.
 */
class TailnetSocketsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = ServerSocket(0, 50, loopback)
    private val bound = mutableListOf<Socket>()
    private val looked = mutableListOf<String>()
    private val dir = Files.createTempDirectory("jotter-hub").toFile()

    @AfterTest
    fun cleanUp() {
        server.close()
        dir.deleteRecursively()
    }

    /**
     * Sockets whose network knows loopback, and the name `hub.invalid` for it, which no real DNS
     * answers.
     */
    private fun sockets(routed: Boolean = true) =
        TailnetSockets({ bound += it }, { routed }) { name ->
            looked += name
            if (name == HUB_NAME || name == "127.0.0.1") listOf(loopback) else emptyList()
        }

    @Test
    fun aSocketIsBoundBeforeItConnects() {
        sockets().createSocket().use { socket ->
            socket.connect(java.net.InetSocketAddress(loopback, server.localPort))
            assertTrue(socket.isConnected)
            assertEquals(listOf(socket), bound)
        }
    }

    @Test
    fun anAddressTheRoutesDoNotCoverIsNotConnected() {
        val socket = sockets(routed = false).createSocket()
        val failure =
            assertFailsWith<IOException> {
                socket.connect(java.net.InetSocketAddress(loopback, server.localPort))
            }
        assertEquals(NOT_THROUGH_TAILSCALE, failure.message)
        assertTrue(bound.isEmpty())
        assertTrue(!socket.isConnected)
    }

    @Test
    fun withoutTheNetworkNothingConnects() {
        val none = TailnetSockets(null, { true }, null)
        assertFailsWith<IOException> { none.createSocket(loopback, server.localPort) }
        val unnamed = assertFailsWith<IOException> { none.createSocket(HUB_NAME, server.localPort) }
        assertEquals(NOT_THROUGH_TAILSCALE, unnamed.message)
        // An address of another kind has nothing to check either.
        assertFailsWith<IOException> {
            sockets().createSocket().connect(object : java.net.SocketAddress() {})
        }
        // A name that is not resolved has no address to check.
        assertFailsWith<IOException> {
            sockets().createSocket().connect(
                java.net.InetSocketAddress.createUnresolved("hub", server.localPort),
            )
        }
    }

    @Test
    fun everyWayOfMakingASocketChecksAndBinds() {
        val factory = sockets()
        val port = server.localPort
        listOf(
            factory.createSocket(loopback, port),
            factory.createSocket("127.0.0.1", port),
            factory.createSocket(loopback, port, loopback, 0),
            factory.createSocket("127.0.0.1", port, loopback, 0),
            factory.createSocket(HUB_NAME, port),
            factory.createSocket(HUB_NAME, port, loopback, 0),
        ).forEach { it.use { socket -> assertTrue(socket.isConnected) } }
        assertEquals(6, bound.size)
        assertEquals(listOf("127.0.0.1", "127.0.0.1", HUB_NAME, HUB_NAME), looked)
    }

    @Test
    fun theHubClientLooksUpEveryNameOnTheNetwork() =
        runBlocking {
            withHub { port, token ->
                PeerBlobStore(PeerAddress(HUB_NAME, port), token, tailnetTransport(sockets())).use {
                    assertEquals(Reachability.Reachable, it.ping())
                    it.writeNew("devices/a/1", byteArrayOf(1))
                    assertEquals(Reachability.Reachable, it.ping())
                }
                // The name is known only to the network, so it was never asked of the system, and
                // it was asked before each of the three requests.
                assertEquals(List(3) { HUB_NAME }, looked)
                // Without the network, a name is not looked up anywhere, and nothing is sent.
                val gone = TailnetSockets(null, { true }, null)
                PeerBlobStore(PeerAddress(HUB_NAME, port), token, tailnetTransport(gone)).use {
                    assertEquals(
                        Reachability.Unreachable(UnreachableReason.FAILED, NOT_THROUGH_TAILSCALE),
                        it.ping(),
                    )
                }
                // A name the network does not know is not found, also not by the system.
                looked.clear()
                val unknown = PeerAddress("localhost", port)
                PeerBlobStore(unknown, token, tailnetTransport(sockets())).use {
                    val unreachable = assertIs<Reachability.Unreachable>(it.ping())
                    assertEquals("Cannot find hub localhost:$port", unreachable.detail)
                }
                assertEquals(listOf("localhost"), looked)
            }
        }

    private suspend fun withHub(body: suspend (port: Int, token: String) -> Unit) {
        val hub =
            PeerHub(
                HubConfig(
                    storeRoot = "${dir.path}/hub".toPath(),
                    tokenFile = "${dir.path}/hub-devices.json".toPath(),
                    host = "127.0.0.1",
                    port = 0,
                ),
            )
        hub.setUpStore()
        val port = hub.start().port
        try {
            body(port, hub.pair("phone").token)
        } finally {
            hub.stop()
        }
    }

    @Test
    fun theHubClientSpeaksToAHubThroughTheseSockets() =
        runBlocking {
            withHub { port, token ->
                val address = PeerAddress("127.0.0.1", port)
                PeerBlobStore(address, token, tailnetTransport(sockets())).use { store ->
                    assertEquals(Reachability.Reachable, store.ping())
                    store.writeNew("devices/a/1", byteArrayOf(1, 2, 3))
                    assertEquals(listOf(BlobInfo("devices/a/1", 3)), store.list("devices"))
                    assertContentEquals(byteArrayOf(1, 2, 3), store.read("devices/a/1"))
                }
                assertTrue(bound.isNotEmpty())
                // Off the tailnet's routes, the hub cannot be reached, and nothing is sent.
                PeerBlobStore(address, token, tailnetTransport(sockets(routed = false))).use {
                    val unreachable = assertIs<Reachability.Unreachable>(it.ping())
                    assertEquals(UnreachableReason.FAILED, unreachable.reason)
                }
            }
        }

    private companion object {
        const val HUB_NAME = "hub.invalid"
    }
}
