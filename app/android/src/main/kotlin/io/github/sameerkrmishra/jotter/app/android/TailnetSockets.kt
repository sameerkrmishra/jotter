package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.peer.PeerTransport
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/** Why a hub's connection was not made: Tailscale's network is gone, or does not lead there. */
internal const val NOT_THROUGH_TAILSCALE: String =
    "Tailscale's network on this phone is gone, or does not lead to the hub, so Jotter did not " +
        "connect"

/**
 * Sockets that leave only through Tailscale's network (ADR-0018). Each is bound with [bind], such
 * as `Network.bindSocket` of the VPN found when the hub was opened, before it connects, and only
 * to an address that [routed] says the VPN's routes cover, asked again at each connection. So if
 * the VPN goes away during a sync, the connection fails instead of leaving by the mobile network.
 * Names are looked up with [resolve], such as `Network.getAllByName` of the same VPN, so that a
 * MagicDNS name is not asked of the mobile network's DNS either.
 *
 * @param bind Binds a socket to the network, or null if there is no such network: then every
 *   connection fails.
 * @param routed Whether the network routes to an address now.
 * @param resolve Looks up a name on the network, or null if there is no such network: then every
 *   lookup fails.
 */
internal class TailnetSockets(
    private val bind: ((Socket) -> Unit)?,
    private val routed: (InetAddress) -> Boolean,
    private val resolve: ((String) -> List<InetAddress>)?,
) : SocketFactory() {
    /**
     * The addresses of [host], looked up only on the network.
     *
     * @throws NoTailnet if there is no network.
     * @throws UnknownHostException if the network knows no address for [host].
     */
    fun lookup(host: String): List<InetAddress> {
        val resolving = resolve ?: throw NoTailnet()
        return resolving(host).ifEmpty { throw UnknownHostException(host) }
    }

    override fun createSocket(): Socket = TailnetSocket()

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(lookup(host).first(), port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket =
        connected(
            InetSocketAddress(lookup(host).first(), port),
            InetSocketAddress(localHost, localPort),
        )

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(
        remote: SocketAddress,
        local: SocketAddress,
    ): Socket =
        createSocket().apply {
            bind(local)
            connect(remote)
        }

    /** A socket that checks and binds itself before it connects. */
    private inner class TailnetSocket : Socket() {
        override fun connect(
            endpoint: SocketAddress,
            timeout: Int,
        ) {
            val target = (endpoint as? InetSocketAddress)?.address
            val binding = bind
            if (binding == null || target == null || !routed(target)) {
                throw IOException(NOT_THROUGH_TAILSCALE)
            }
            binding(this)
            super.connect(endpoint, timeout)
        }
    }
}

/** A lookup without Tailscale's network: [NOT_THROUGH_TAILSCALE]. */
internal class NoTailnet : UnknownHostException(NOT_THROUGH_TAILSCALE)

/**
 * How the hub client reaches a hub on [sockets]: by [tailnetEngine], after looking up the hub's
 * name with [TailnetSockets.lookup] before each request. Without the network, the hub is
 * unreachable with [NOT_THROUGH_TAILSCALE], and nothing is looked up or sent.
 */
internal fun tailnetTransport(sockets: TailnetSockets): PeerTransport =
    PeerTransport(tailnetEngine(sockets)) { host ->
        try {
            sockets.lookup(host).first().address
        } catch (_: NoTailnet) {
            throw BackendUnavailableException(
                Reachability.Unreachable(UnreachableReason.FAILED, NOT_THROUGH_TAILSCALE),
            )
        }
    }

/**
 * The hub client's HTTP engine on [sockets]: OkHttp, as Ktor's CIO takes no socket factory. Every
 * name it looks up, for each request, goes through [TailnetSockets.lookup] and never the system's
 * DNS. Like the client's own engine, it retries nothing, follows no redirect and uses no proxy,
 * which could take the token elsewhere.
 */
internal fun tailnetEngine(sockets: TailnetSockets): HttpClientEngine =
    OkHttp.create {
        config {
            socketFactory(sockets)
            dns { sockets.lookup(it) }
            proxy(Proxy.NO_PROXY)
            retryOnConnectionFailure(false)
            followRedirects(false)
            followSslRedirects(false)
        }
    }
