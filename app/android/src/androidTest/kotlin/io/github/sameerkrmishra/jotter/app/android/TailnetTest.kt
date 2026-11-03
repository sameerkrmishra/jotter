package io.github.sameerkrmishra.jotter.app.android

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Secret
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Inet4Address
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * On a phone without Tailscale, as this emulator is, a hub's token goes to loopback only, and the
 * app's own backend manager refuses a Tailscale address at once.
 */
@RunWith(AndroidJUnit4::class)
class TailnetTest {
    private val test = TestGraph()

    @After
    fun cleanUp() {
        test.close()
    }

    @Test
    fun withoutTailscaleOnlyLoopbackGetsAToken() {
        val check = AndroidTailnet(test.context.getSystemService(ConnectivityManager::class.java))
        assertEquals(TAILSCALE_OFF, check.refuse("100.101.102.103"))
        assertEquals(TAILSCALE_OFF, check.refuse("pc.tail1234.ts.net"))
        assertNull(check.refuse("127.0.0.1"))
    }

    @Test
    fun aSocketBoundToANetworkConnectsOnlyWhereItsRoutesLead() {
        val connectivity = test.context.getSystemService(ConnectivityManager::class.java)
        // The emulator's own network stands in for Tailscale's, which it does not have.
        val network = checkNotNull(connectivity.activeNetwork)
        val links = checkNotNull(connectivity.getLinkProperties(network))
        val own = links.linkAddresses.map { it.address }.first { it is Inet4Address }
        ServerSocket(0, 1, own).use { server ->
            val lookup = { host: String -> network.getAllByName(host).toList() }
            val routes = { a: java.net.InetAddress -> links.routes.any { it.matches(a) } }
            val routed = TailnetSockets(network::bindSocket, routes, lookup)
            routed.createSocket(own, server.localPort).use { assertTrue(it.isConnected) }
            // A name is looked up on the network, as the hub client's engine does.
            val literal = checkNotNull(own.hostAddress)
            routed.createSocket(literal, server.localPort).use { assertTrue(it.isConnected) }
            assertEquals(listOf(own), routed.lookup(literal))
            val nowhere = TailnetSockets(network::bindSocket, { false }, lookup)
            assertFailsWith<IOException> { nowhere.createSocket(own, server.localPort) }
        }
        val check = AndroidTailnet(connectivity)
        assertNull(check.transport("127.0.0.1"), "loopback uses the client's own transport")
        checkNotNull(check.transport("100.101.102.103")).engine.close()
    }

    @Test
    fun theAppRefusesAHubOffTheTailnetWithoutTryingIt() =
        runBlocking {
            val manager = test.ready.manager
            val took =
                measureTime {
                    val added = manager.addPeer("100.101.102.103", Secret("id.secret"))
                    assertEquals(
                        "Cannot reach the hub at 100.101.102.103:47734: $TAILSCALE_OFF. Check " +
                            "that the hub is on and that Tailscale runs on both devices.",
                        assertIs<AddResult.Refused>(added).reason,
                    )
                }
            // Not the client's 10 s connect timeout: nothing was tried.
            assertTrue(took < 5.seconds, "took $took")
            assertEquals(emptyList(), manager.list())
        }
}
