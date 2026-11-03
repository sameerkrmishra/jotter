package io.github.sameerkrmishra.jotter.app.android

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a hub's token may leave the phone: the rule, on the JVM. */
class TailscaleTest {
    private fun at(vararg literals: String) = literals.map { InetAddress.getByName(it) }

    @Test
    fun loopbackMayAlways() {
        listOf("127.0.0.1", "127.8.9.10", "localhost", "::1", "[::1]").forEach { host ->
            assertNull(tailnetRefusal(host, null), host)
        }
    }

    @Test
    fun anythingElseOnlyWhileAVpnHoldsATailscaleAddress() {
        val hosts =
            listOf(
                "100.101.102.103",
                "pc.tail1234.ts.net",
                "128.0.0.1",
                "127.0.0",
                "1270.0.0.1",
                "127.0.0.256",
            )
        hosts.forEach { host ->
            assertEquals(TAILSCALE_OFF, tailnetRefusal(host, null), host)
            assertEquals(TAILSCALE_OFF, tailnetRefusal(host, emptyList()), host)
            // Another VPN, such as a work one, is not the tailnet.
            assertEquals(TAILSCALE_OFF, tailnetRefusal(host, at("10.8.0.2", "fd00::2")), host)
            assertEquals(
                TAILSCALE_OFF,
                tailnetRefusal(host, at("100.128.0.1", "100.63.255.255")),
                host,
            )
            assertNull(tailnetRefusal(host, at("10.8.0.2", "100.64.0.1")), host)
            assertNull(tailnetRefusal(host, at("100.127.255.254")), host)
            assertNull(tailnetRefusal(host, at("fd7a:115c:a1e0::1")), host)
            assertEquals(TAILSCALE_OFF, tailnetRefusal(host, at("fd7a:115c:a1e1::1")), host)
        }
    }
}
