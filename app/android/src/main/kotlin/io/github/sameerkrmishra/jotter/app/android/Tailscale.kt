package io.github.sameerkrmishra.jotter.app.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.sameerkrmishra.jotter.app.sync.TailnetCheck
import java.net.Inet4Address
import java.net.InetAddress

private const val IPV4_PARTS = 4
private const val MAX_OCTET = 255
private const val LOOPBACK_FIRST = 127
private const val TAILSCALE_FIRST = 100
private const val TAILSCALE_MASK = 0xC0
private const val TAILSCALE_SECOND = 0x40
private const val BYTE = 0xFF

/** Tailscale's IPv6 range, `fd7a:115c:a1e0::/48`, as its first six bytes. */
private val TAILSCALE_V6 = listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)

/** What a hub says while Tailscale does not run on this phone (ADR-0018). */
internal const val TAILSCALE_OFF: String =
    "Tailscale is not running on this phone, so Jotter does not send the hub its pairing " +
        "token: the hub's address could belong to someone else on the mobile network. Turn " +
        "Tailscale on, and Jotter syncs with the hub again"

/**
 * Why a hub's token must not go to [host] now, or null if it may (ADR-0018). Loopback always may,
 * as for the end-to-end test. Anything else, a Tailscale address or a MagicDNS name, only while
 * the network that Android gives this app is a VPN holding a Tailscale address, as Tailscale's app
 * makes. [vpnAddresses] are that network's addresses if it is a VPN, or null if it is not.
 */
internal fun tailnetRefusal(
    host: String,
    vpnAddresses: List<InetAddress>?,
): String? =
    when {
        isLoopback(host) -> null
        vpnAddresses.orEmpty().any(::isTailscale) -> null
        else -> TAILSCALE_OFF
    }

private fun isLoopback(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..MAX_OCTET } }
    val ipv4 = host.count { it == '.' } == IPV4_PARTS - 1 && octets.size == IPV4_PARTS
    return host == "localhost" ||
        host == "::1" ||
        host == "[::1]" ||
        (ipv4 && octets.first() == LOOPBACK_FIRST)
}

/** Whether [address] is in `100.64.0.0/10` or `fd7a:115c:a1e0::/48`. */
private fun isTailscale(address: InetAddress): Boolean {
    val bytes = address.address.map { it.toInt() and BYTE }
    // An InetAddress is either of the two.
    return if (address is Inet4Address) {
        bytes[0] == TAILSCALE_FIRST && bytes[1] and TAILSCALE_MASK == TAILSCALE_SECOND
    } else {
        bytes.take(TAILSCALE_V6.size) == TAILSCALE_V6
    }
}

/**
 * The [TailnetCheck] of a phone: it asks Android whether this app's network is Tailscale's VPN
 * right now, each time a hub is opened. Tailscale's Android app is a VPN whose interface holds the
 * phone's Tailscale address; without it, a `100.64.0.0/10` address goes out over the mobile
 * network, where carriers use that range themselves. An app that Tailscale's split tunnelling
 * leaves out sees no VPN either, and so sends nothing.
 */
internal class AndroidTailnet(
    private val connectivity: ConnectivityManager,
) : TailnetCheck {
    override fun refuse(host: String): String? {
        val network = connectivity.activeNetwork
        val vpn =
            network
                ?.let(connectivity::getNetworkCapabilities)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val addresses =
            if (vpn) {
                connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            } else {
                null
            }
        return tailnetRefusal(host, addresses)
    }
}
