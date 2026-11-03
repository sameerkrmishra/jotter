package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.backend.peer.PeerProtocol
import okio.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val MAX_PORT = 65535

/**
 * How a [PeerHub] runs.
 *
 * The hub listens on [host] only, which must be an IPv4 address of this machine: loopback, or its
 * Tailscale address. Any other address, including `0.0.0.0`, is refused, because the hub speaks
 * plain HTTP and relies on Tailscale's WireGuard tunnel for encryption
 * (`docs/adr/0015-pc-hub-peer-protocol.md`). See [isAllowedBindAddress].
 *
 * @property storeRoot The folder the hub serves. The PC's own sync uses the same folder as a local
 *   folder backend.
 * @property tokenFile Where the paired devices are kept. It holds hashes, never a token.
 * @property host The address to listen on: `127.0.0.1` by default.
 * @property port The TCP port; 0 picks a free one, which [HubStatus.Running] then reports.
 * @property limits How much one hub lets its clients use.
 * @throws IllegalArgumentException if [host] is not an allowed address or [port] is out of range.
 */
public data class HubConfig(
    val storeRoot: Path,
    val tokenFile: Path,
    val host: String = LOOPBACK,
    val port: Int = PeerProtocol.DEFAULT_PORT,
    val limits: HubLimits = HubLimits(),
) {
    init {
        require(isAllowedBindAddress(host)) {
            "The hub only listens on loopback (127.0.0.0/8) or a Tailscale address " +
                "(100.64.0.0/10), written as an IPv4 address, not on \"$host\""
        }
        require(port in 0..MAX_PORT) { "$port is not a TCP port" }
    }

    /** Constants. */
    public companion object {
        /** The default address: this machine only. */
        public const val LOOPBACK: String = "127.0.0.1"
    }
}

/**
 * How much the hub lets its clients use, so that a broken or hostile client cannot exhaust the PC.
 * Memory is bounded by [maxTransfers] times `PeerProtocol.MAX_FILE_BYTES`, and sockets by
 * [maxConnections]: further connections wait in the system's queue until one ends.
 *
 * @property maxConnections Connections open at once, counted until their sockets have closed.
 * @property maxConnectionsPerClient Connections open at once from one address, with a token or
 *   not, so that one machine on the tailnet cannot take every slot. Further ones are cut at once.
 * @property maxTransfers Reads and writes of a file at once; each may hold a whole file in memory.
 * @property headerTimeout How long a client may take to send the request line and headers.
 * @property idleTimeout How long a body may stall before the request is dropped.
 * @property requestDeadline How long one request may take in all, from its first byte to the end
 *   of the answer. The default leaves room for 32 MiB at about half a megabit per second.
 * @throws IllegalArgumentException if a limit is not positive.
 */
public data class HubLimits(
    val maxConnections: Int = 16,
    val maxConnectionsPerClient: Int = 8,
    val maxTransfers: Int = 4,
    val headerTimeout: Duration = 10.seconds,
    val idleTimeout: Duration = 30.seconds,
    val requestDeadline: Duration = 15.minutes,
) {
    init {
        require(maxConnections > 0 && maxConnectionsPerClient > 0 && maxTransfers > 0) {
            "Limits must be positive"
        }
        require(headerTimeout.isPositive() && idleTimeout.isPositive()) {
            "Timeouts must be positive"
        }
        require(requestDeadline.isPositive()) { "Timeouts must be positive" }
    }
}

/**
 * Whether the hub may listen on [host]: an IPv4 address, in dotted decimal, in loopback
 * (`127.0.0.0/8`) or in Tailscale's range (`100.64.0.0/10`). No host names, since a name could
 * resolve to anything, and no wildcard. Tailscale's range is the shared address space of carrier
 * grade NAT, so this cannot prove that an address is Tailscale's; it rules out the usual LAN and
 * public addresses.
 */
public fun isAllowedBindAddress(host: String): Boolean {
    val octets = ipv4Octets(host) ?: return false
    return octets[0] == LOOPBACK_FIRST_OCTET ||
        (octets[0] == TAILSCALE_FIRST_OCTET && octets[1] and TAILSCALE_MASK == TAILSCALE_SECOND)
}

private const val LOOPBACK_FIRST_OCTET = 127
private const val TAILSCALE_FIRST_OCTET = 100

// 100.64.0.0/10: the top two bits of the second octet are 01.
private const val TAILSCALE_MASK = 0xC0
private const val TAILSCALE_SECOND = 0x40
private const val OCTETS = 4
private const val MAX_OCTET = 255
private const val MAX_OCTET_DIGITS = 3

/** The four octets of a dotted decimal IPv4 address without leading zeros, or null. */
private fun ipv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != OCTETS) return null
    val wellFormed =
        parts.all { part ->
            part.length in 1..MAX_OCTET_DIGITS &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() <= MAX_OCTET
        }
    return if (wellFormed) parts.map { it.toInt() } else null
}
