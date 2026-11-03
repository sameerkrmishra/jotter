package io.github.sameerkrmishra.jotter.app.android

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
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
 *
 * @param bind Binds a socket to the network, or null if there is no such network: then every
 *   connection fails.
 * @param routed Whether the network routes to an address now.
 */
internal class TailnetSockets(
    private val bind: ((Socket) -> Unit)?,
    private val routed: (InetAddress) -> Boolean,
) : SocketFactory() {
    override fun createSocket(): Socket = TailnetSocket()

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

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

/**
 * The hub client's HTTP engine on [sockets]: OkHttp, as Ktor's CIO takes no socket factory. Like
 * the client's own engine, it retries nothing, follows no redirect and uses no proxy, which could
 * take the token elsewhere.
 */
internal fun tailnetEngine(sockets: SocketFactory): HttpClientEngine =
    OkHttp.create {
        config {
            socketFactory(sockets)
            proxy(Proxy.NO_PROXY)
            retryOnConnectionFailure(false)
            followRedirects(false)
            followSslRedirects(false)
        }
    }
